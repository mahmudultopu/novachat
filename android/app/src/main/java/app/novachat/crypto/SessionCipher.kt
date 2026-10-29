package app.novachat.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** This install's long-term identity: one key for X3DH, one that signs prekeys. */
class IdentityKeys(val dh: DhKeyPair, val signing: SigningKeyPair) {
    companion object {
        fun generate() = IdentityKeys(DhKeyPair.generate(), SigningKeyPair.generate())
    }
}

class SignedPreKey(val id: Int, val keyPair: DhKeyPair, val signature: ByteArray)

class OneTimePreKey(val id: Int, val keyPair: DhKeyPair)

/** What the key directory hands out so someone can start a session with us. */
class PreKeyBundle(
    val identityKey: ByteArray,
    val signingKey: ByteArray,
    val signedPreKeyId: Int,
    val signedPreKey: ByteArray,
    val signedPreKeySignature: ByteArray,
    val oneTimePreKeyId: Int?,
    val oneTimePreKey: ByteArray?,
)

/** Where the cipher keeps its secrets. The app backs this with the encrypted database. */
interface KeyStore {
    suspend fun identity(): IdentityKeys
    suspend fun signedPreKey(id: Int): SignedPreKey?
    /** Returns and deletes a one-time prekey, so it is used at most once. */
    suspend fun takeOneTimePreKey(id: Int): OneTimePreKey?
    /** Sessions with one person, the one to send with first. */
    suspend fun sessions(peerId: String): List<SessionState>
    suspend fun saveSessions(peerId: String, sessions: List<SessionState>)
}

/** The wire format of one encrypted message (sent as a JSON string). */
@Serializable
data class Envelope(
    val v: Int = 1,
    /** Present until the recipient has replied: lets it build the session. */
    val pre: PreKeyHeader? = null,
    val dh: String,
    val pn: Int,
    val n: Int,
    val ct: String,
)

@Serializable
data class PreKeyHeader(
    val ik: String,
    val iks: String,
    val ek: String,
    val spk: Int,
    val opk: Int? = null,
)

class Decrypted(val plaintext: ByteArray, val remoteIdentityKey: ByteArray, val newSession: Boolean)

/**
 * End-to-end encryption between two people: X3DH to agree on a first secret
 * from published prekeys (https://signal.org/docs/specifications/x3dh/), then
 * a Double Ratchet session for every message after that. The server only ever
 * sees [Envelope]s it cannot open.
 */
class SessionCipher(private val store: KeyStore) {

    suspend fun hasSession(peerId: String) = store.sessions(peerId).isNotEmpty()

    /** The identity key our current session with [peerId] encrypts to. */
    suspend fun remoteIdentity(peerId: String): ByteArray? = store.sessions(peerId).firstOrNull()?.remoteIdentityKey

    /** Starts a session from someone's published bundle (X3DH, initiator side). */
    suspend fun startSession(peerId: String, bundle: PreKeyBundle) {
        if (!SigningKeyPair.verify(bundle.signingKey, bundle.signedPreKeySignature, bundle.signedPreKey)) {
            throw CryptoException("signed prekey signature is invalid")
        }
        val me = store.identity()
        val ephemeral = DhKeyPair.generate()
        val dh1 = me.dh.agree(bundle.signedPreKey)
        val dh2 = ephemeral.agree(bundle.identityKey)
        val dh3 = ephemeral.agree(bundle.signedPreKey)
        val dh4 = bundle.oneTimePreKey?.let { ephemeral.agree(it) } ?: ByteArray(0)
        val secret = sharedSecret(dh1 + dh2 + dh3 + dh4)
        val ad = me.dh.publicKey + me.signing.publicKey + bundle.identityKey + bundle.signingKey
        val session = SessionState.initiator(
            secret, ad, bundle.identityKey, bundle.signingKey, bundle.signedPreKey,
            PendingPreKey(ephemeral.publicKey, bundle.signedPreKeyId, bundle.oneTimePreKeyId),
        )
        store.saveSessions(peerId, listOf(session) + store.sessions(peerId).take(MAX_SESSIONS - 1))
    }

    suspend fun encrypt(peerId: String, plaintext: ByteArray): Envelope {
        val sessions = store.sessions(peerId)
        val session = sessions.firstOrNull()?.deepCopy() ?: throw CryptoException("no session")
        val msg = session.encrypt(plaintext)
        val me = store.identity()
        val pre = session.pendingPreKey?.let {
            PreKeyHeader(me.dh.publicKey.b64(), me.signing.publicKey.b64(), it.ephemeralKey.b64(), it.signedPreKeyId, it.oneTimePreKeyId)
        }
        store.saveSessions(peerId, listOf(session) + sessions.drop(1))
        return Envelope(pre = pre, dh = msg.header.dh.b64(), pn = msg.header.pn, n = msg.header.n, ct = msg.ciphertext.b64())
    }

    suspend fun decrypt(peerId: String, envelope: Envelope): Decrypted {
        if (envelope.v != 1) throw CryptoException("unsupported version ${envelope.v}")
        val message = RatchetMessage(Header(envelope.dh.unb64(), envelope.pn, envelope.n), envelope.ct.unb64())
        val sessions = store.sessions(peerId)

        // Try every session we hold with this person, newest first. The one
        // that works becomes current, which also settles the case where both
        // people started a session at the same time.
        for ((i, s) in sessions.withIndex()) {
            if (envelope.pre != null && !s.baseKey.contentEquals(envelope.pre.ek.unb64())) continue
            val copy = s.deepCopy()
            val pt = try {
                copy.decrypt(message)
            } catch (e: CryptoException) {
                continue
            }
            store.saveSessions(peerId, listOf(copy) + sessions.filterIndexed { j, _ -> j != i })
            return Decrypted(pt, copy.remoteIdentityKey, newSession = false)
        }

        val pre = envelope.pre ?: throw CryptoException("no session for this message")
        val session = respond(pre)
        val pt = session.decrypt(message)
        store.saveSessions(peerId, listOf(session) + sessions.take(MAX_SESSIONS - 1))
        return Decrypted(pt, session.remoteIdentityKey, newSession = true)
    }

    /** X3DH on the receiving side. */
    private suspend fun respond(pre: PreKeyHeader): SessionState {
        val me = store.identity()
        val spk = store.signedPreKey(pre.spk) ?: throw CryptoException("unknown signed prekey ${pre.spk}")
        val opk = pre.opk?.let { store.takeOneTimePreKey(it) ?: throw CryptoException("one-time prekey ${pre.opk} already used") }
        val theirIdentity = pre.ik.unb64()
        val ephemeral = pre.ek.unb64()
        val dh1 = spk.keyPair.agree(theirIdentity)
        val dh2 = me.dh.agree(ephemeral)
        val dh3 = spk.keyPair.agree(ephemeral)
        val dh4 = opk?.keyPair?.agree(ephemeral) ?: ByteArray(0)
        val secret = sharedSecret(dh1 + dh2 + dh3 + dh4)
        val theirSigning = pre.iks.unb64()
        val ad = theirIdentity + theirSigning + me.dh.publicKey + me.signing.publicKey
        return SessionState.responder(secret, ad, theirIdentity, theirSigning, ephemeral, spk.keyPair)
    }

    private fun sharedSecret(dhs: ByteArray): ByteArray =
        Crypto.hkdf(ByteArray(32) { 0xFF.toByte() } + dhs, ByteArray(32), "NovaChat X3DH", 32)

    companion object {
        const val MAX_SESSIONS = 5

        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /**
         * The 60-digit safety number two people compare to rule out a
         * man-in-the-middle. Both phones compute the same digits.
         */
        fun safetyNumber(myId: String, myKey: ByteArray, theirId: String, theirKey: ByteArray): String {
            fun half(id: String, key: ByteArray): String {
                var hash = id.toByteArray() + key
                repeat(5200) { hash = Crypto.sha512(hash, key) }
                return (0 until 6).joinToString("") { i ->
                    val chunk = hash.copyOfRange(i * 5, i * 5 + 5)
                    val v = chunk.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                    (v % 100_000).toString().padStart(5, '0')
                }
            }
            val mine = half(myId, myKey)
            val theirs = half(theirId, theirKey)
            return if (myId < theirId) mine + theirs else theirs + mine
        }
    }
}
