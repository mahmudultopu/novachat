package app.novachat.crypto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.ByteBuffer

internal object B64 : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("B64", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(value.b64())
    override fun deserialize(decoder: Decoder): ByteArray = decoder.decodeString().unb64()
}

/** The X3DH values an initiator repeats on every message until the first reply. */
@Serializable
data class PendingPreKey(
    @Serializable(with = B64::class) val ephemeralKey: ByteArray,
    val signedPreKeyId: Int,
    val oneTimePreKeyId: Int? = null,
)

@Serializable
data class SkippedKey(
    @Serializable(with = B64::class) val dh: ByteArray,
    val n: Int,
    @Serializable(with = B64::class) val key: ByteArray,
)

/**
 * One Double Ratchet session with one other device, as in Signal's spec
 * (https://signal.org/docs/specifications/doubleratchet/). Every message gets
 * a fresh key; old keys are deleted, so a stolen phone cannot read past
 * messages (forward secrecy), and each reply mixes in a new Diffie-Hellman
 * exchange, so a leaked key heals itself (post-compromise security).
 */
@Serializable
data class SessionState(
    /** Identity keys of both sides, bound into every message as associated data. */
    @Serializable(with = B64::class) val associatedData: ByteArray,
    @Serializable(with = B64::class) val remoteIdentityKey: ByteArray,
    @Serializable(with = B64::class) val remoteSigningKey: ByteArray,
    /** The initiator's ephemeral key, which names this session. */
    @Serializable(with = B64::class) val baseKey: ByteArray,
    @Serializable(with = B64::class) var rootKey: ByteArray,
    @Serializable(with = B64::class) var sendingPrivate: ByteArray,
    @Serializable(with = B64::class) var sendingPublic: ByteArray,
    @Serializable(with = B64::class) var receivingKey: ByteArray? = null,
    @Serializable(with = B64::class) var sendingChain: ByteArray? = null,
    @Serializable(with = B64::class) var receivingChain: ByteArray? = null,
    var sendCount: Int = 0,
    var receiveCount: Int = 0,
    var previousCount: Int = 0,
    var skipped: List<SkippedKey> = emptyList(),
    var pendingPreKey: PendingPreKey? = null,
) {
    fun deepCopy(): SessionState = copy(skipped = skipped.toList())

    /** Encrypts one message and advances the sending chain. */
    fun encrypt(plaintext: ByteArray): RatchetMessage {
        val chain = sendingChain ?: throw CryptoException("session cannot send yet")
        val (messageKey, next) = chainStep(chain)
        sendingChain = next
        val header = Header(sendingPublic, previousCount, sendCount)
        sendCount++
        val ct = Crypto.seal(messageKey, plaintext, associatedData + header.bytes())
        return RatchetMessage(header, ct)
    }

    /** Decrypts one message. On failure the caller must discard this (copied) state. */
    fun decrypt(message: RatchetMessage): ByteArray {
        val h = message.header
        skipped.firstOrNull { it.n == h.n && it.dh.contentEquals(h.dh) }?.let { s ->
            val pt = Crypto.open(s.key, message.ciphertext, associatedData + h.bytes())
            skipped = skipped - s
            return pt
        }
        if (receivingKey?.contentEquals(h.dh) != true) {
            skipTo(h.pn)
            dhRatchet(h.dh)
        }
        skipTo(h.n)
        val (messageKey, next) = chainStep(receivingChain ?: throw CryptoException("no receiving chain"))
        receivingChain = next
        receiveCount++
        val pt = Crypto.open(messageKey, message.ciphertext, associatedData + h.bytes())
        pendingPreKey = null // the other side replied, so it has this session
        return pt
    }

    private fun skipTo(until: Int) {
        val chain = receivingChain ?: return
        if (until - receiveCount > MAX_SKIP) throw CryptoException("too many skipped messages")
        var ck = chain
        val dh = receivingKey ?: return
        val added = mutableListOf<SkippedKey>()
        while (receiveCount < until) {
            val (mk, next) = chainStep(ck)
            added += SkippedKey(dh, receiveCount, mk)
            ck = next
            receiveCount++
        }
        receivingChain = ck
        skipped = (skipped + added).takeLast(MAX_STORED_SKIPPED)
    }

    private fun dhRatchet(theirKey: ByteArray) {
        previousCount = sendCount
        sendCount = 0
        receiveCount = 0
        receivingKey = theirKey
        val ours = DhKeyPair(sendingPrivate, sendingPublic)
        rootStep(ours.agree(theirKey)).let { (rk, ck) -> rootKey = rk; receivingChain = ck }
        val fresh = DhKeyPair.generate()
        sendingPrivate = fresh.privateKey
        sendingPublic = fresh.publicKey
        rootStep(fresh.agree(theirKey)).let { (rk, ck) -> rootKey = rk; sendingChain = ck }
    }

    private fun rootStep(dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val out = Crypto.hkdf(dhOut, rootKey, "NovaChat ratchet", 64)
        return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
    }

    companion object {
        const val MAX_SKIP = 2000
        const val MAX_STORED_SKIPPED = 2000

        private fun chainStep(chainKey: ByteArray): Pair<ByteArray, ByteArray> =
            Crypto.hmac(chainKey, byteArrayOf(1)) to Crypto.hmac(chainKey, byteArrayOf(2))

        /** Starts the session on the side that sent the first message. */
        fun initiator(
            sharedSecret: ByteArray,
            associatedData: ByteArray,
            remoteIdentityKey: ByteArray,
            remoteSigningKey: ByteArray,
            remoteSignedPreKey: ByteArray,
            pending: PendingPreKey,
        ): SessionState {
            val sending = DhKeyPair.generate()
            val out = Crypto.hkdf(sending.agree(remoteSignedPreKey), sharedSecret, "NovaChat ratchet", 64)
            return SessionState(
                associatedData = associatedData,
                remoteIdentityKey = remoteIdentityKey,
                remoteSigningKey = remoteSigningKey,
                baseKey = pending.ephemeralKey,
                rootKey = out.copyOfRange(0, 32),
                sendingPrivate = sending.privateKey,
                sendingPublic = sending.publicKey,
                receivingKey = remoteSignedPreKey,
                sendingChain = out.copyOfRange(32, 64),
                pendingPreKey = pending,
            )
        }

        /** Starts the session on the side that received the first message. */
        fun responder(
            sharedSecret: ByteArray,
            associatedData: ByteArray,
            remoteIdentityKey: ByteArray,
            remoteSigningKey: ByteArray,
            baseKey: ByteArray,
            signedPreKey: DhKeyPair,
        ) = SessionState(
            associatedData = associatedData,
            remoteIdentityKey = remoteIdentityKey,
            remoteSigningKey = remoteSigningKey,
            baseKey = baseKey,
            rootKey = sharedSecret,
            sendingPrivate = signedPreKey.privateKey,
            sendingPublic = signedPreKey.publicKey,
        )
    }
}

class Header(val dh: ByteArray, val pn: Int, val n: Int) {
    fun bytes(): ByteArray = ByteBuffer.allocate(dh.size + 8).put(dh).putInt(pn).putInt(n).array()
}

class RatchetMessage(val header: Header, val ciphertext: ByteArray)
