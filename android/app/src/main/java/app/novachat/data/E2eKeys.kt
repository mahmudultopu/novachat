package app.novachat.data

import app.novachat.crypto.DhKeyPair
import app.novachat.crypto.IdentityKeys
import app.novachat.crypto.KeyStore
import app.novachat.crypto.OneTimePreKey
import app.novachat.crypto.SessionCipher
import app.novachat.crypto.SessionState
import app.novachat.crypto.SignedPreKey
import app.novachat.crypto.SigningKeyPair
import app.novachat.crypto.b64
import app.novachat.crypto.unb64
import app.novachat.data.local.ChatDao
import app.novachat.data.local.MetaEntity
import app.novachat.data.local.PreKeyEntity
import app.novachat.data.local.SessionEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * This phone's end-to-end encryption keys and sessions, stored in the chat
 * database and sealed with the Keystore [Vault]. Also keeps the server's key
 * directory stocked with prekeys.
 */
class E2eKeys(private val dao: ChatDao, private val vault: Vault) : KeyStore {
    @Serializable
    private data class StoredIdentity(val dh: String, val signing: String, val signingPublic: String)

    @Serializable
    private data class StoredSignedPreKey(val id: Int, val privateKey: String, val signature: String, val createdAt: Long)

    private val json = SessionCipher.json
    private val lock = Mutex()
    private var cached: IdentityKeys? = null

    /** Forget cached keys after the database was wiped (log out). */
    fun reset() {
        cached = null
    }

    override suspend fun identity(): IdentityKeys = cached ?: lock.withLock {
        cached ?: (loadIdentity() ?: createIdentity()).also { cached = it }
    }

    private suspend fun loadIdentity(): IdentityKeys? {
        val stored = dao.meta(IDENTITY)?.let(vault::open)?.let { json.decodeFromString<StoredIdentity>(it) } ?: return null
        return IdentityKeys(
            DhKeyPair.fromPrivate(stored.dh.unb64()),
            SigningKeyPair(stored.signing.unb64(), stored.signingPublic.unb64()),
        )
    }

    private suspend fun createIdentity(): IdentityKeys {
        // Whatever was encrypted to an older identity cannot be used any more.
        dao.deleteSessions()
        dao.deletePreKeys()
        dao.deleteMetaKey(SPK_CURRENT)
        dao.deleteMetaKey(SPK_PREVIOUS)
        val keys = IdentityKeys.generate()
        val stored = StoredIdentity(keys.dh.privateKey.b64(), keys.signing.privateKey.b64(), keys.signing.publicKey.b64())
        dao.putMeta(MetaEntity(IDENTITY, vault.seal(json.encodeToString(stored))))
        return keys
    }

    override suspend fun signedPreKey(id: Int): SignedPreKey? =
        listOf(SPK_CURRENT, SPK_PREVIOUS).firstNotNullOfOrNull { k -> loadSignedPreKey(k)?.takeIf { it.id == id } }

    private suspend fun loadSignedPreKey(key: String): SignedPreKey? {
        val s = dao.meta(key)?.let(vault::open)?.let { json.decodeFromString<StoredSignedPreKey>(it) } ?: return null
        return SignedPreKey(s.id, DhKeyPair.fromPrivate(s.privateKey.unb64()), s.signature.unb64())
    }

    /** The signed prekey to publish, replaced weekly. The previous one is kept for late messages. */
    private suspend fun currentSignedPreKey(): SignedPreKey {
        val raw = dao.meta(SPK_CURRENT)?.let(vault::open)?.let { json.decodeFromString<StoredSignedPreKey>(it) }
        if (raw != null && System.currentTimeMillis() - raw.createdAt < SPK_LIFETIME_MS) {
            return SignedPreKey(raw.id, DhKeyPair.fromPrivate(raw.privateKey.unb64()), raw.signature.unb64())
        }
        val pair = DhKeyPair.generate()
        val spk = SignedPreKey((raw?.id ?: 0) + 1, pair, identity().signing.sign(pair.publicKey))
        dao.meta(SPK_CURRENT)?.let { dao.putMeta(MetaEntity(SPK_PREVIOUS, it)) }
        val stored = StoredSignedPreKey(spk.id, pair.privateKey.b64(), spk.signature.b64(), System.currentTimeMillis())
        dao.putMeta(MetaEntity(SPK_CURRENT, vault.seal(json.encodeToString(stored))))
        return spk
    }

    override suspend fun takeOneTimePreKey(id: Int): OneTimePreKey? {
        val priv = dao.preKey(id)?.let(vault::open) ?: return null
        dao.deletePreKey(id)
        return OneTimePreKey(id, DhKeyPair.fromPrivate(priv.unb64()))
    }

    override suspend fun sessions(peerId: String): List<SessionState> =
        dao.session(peerId)?.let(vault::open)?.let { json.decodeFromString<List<SessionState>>(it) }.orEmpty()

    override suspend fun saveSessions(peerId: String, sessions: List<SessionState>) {
        dao.putSession(SessionEntity(peerId, vault.seal(json.encodeToString(sessions))))
    }

    suspend fun forgetSessions(peerId: String) = dao.deleteSession(peerId)

    /** Publishes our public keys and tops up one-time prekeys when they run low. */
    suspend fun publish(api: ApiClient, session: Session) {
        val id = identity()
        val spk = currentSignedPreKey()
        val request = PublishKeysRequest(
            identityKey = id.dh.publicKey.b64(),
            signingKey = id.signing.publicKey.b64(),
            signedPreKey = SignedPreKeyDto(spk.id, spk.keyPair.publicKey.b64(), spk.signature.b64()),
        )
        val left = api.publishKeys(session, request).oneTimePreKeys
        if (left >= LOW_WATER) return
        val first = dao.meta(NEXT_PREKEY_ID)?.toIntOrNull() ?: 1
        val fresh = (first until first + BATCH).map { OneTimePreKey(it, DhKeyPair.generate()) }
        dao.insertPreKeys(fresh.map { PreKeyEntity(it.id, vault.seal(it.keyPair.privateKey.b64())) })
        dao.putMeta(MetaEntity(NEXT_PREKEY_ID, (first + BATCH).toString()))
        api.publishKeys(session, request.copy(oneTimePreKeys = fresh.map { OneTimePreKeyDto(it.id, it.keyPair.publicKey.b64()) }))
    }

    private companion object {
        const val IDENTITY = "e2e_identity"
        const val SPK_CURRENT = "e2e_spk_current"
        const val SPK_PREVIOUS = "e2e_spk_previous"
        const val NEXT_PREKEY_ID = "e2e_next_prekey_id"
        const val SPK_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
        const val LOW_WATER = 25
        const val BATCH = 100
    }
}
