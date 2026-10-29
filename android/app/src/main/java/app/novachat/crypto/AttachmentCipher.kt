package app.novachat.crypto

import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** An encrypted file, plus what the recipient needs to open and check it. */
class EncryptedAttachment(val ciphertext: ByteArray, val key: ByteArray, val digest: ByteArray)

/**
 * Encrypts photos, videos, voice notes and documents before upload. Each file
 * gets a fresh AES-256-GCM key; the key and the ciphertext's SHA-256 travel
 * inside the end-to-end encrypted message, so the server stores only bytes it
 * cannot read or swap.
 */
object AttachmentCipher {
    private const val NONCE = 12

    fun encrypt(plaintext: ByteArray): EncryptedAttachment {
        val key = Crypto.randomBytes(32)
        val nonce = Crypto.randomBytes(NONCE)
        val ct = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(plaintext)
        }
        val out = nonce + ct
        return EncryptedAttachment(out, key, sha256(out))
    }

    fun decrypt(ciphertext: ByteArray, key: ByteArray, digest: ByteArray): ByteArray {
        if (!MessageDigest.isEqual(sha256(ciphertext), digest)) throw CryptoException("attachment digest mismatch")
        if (ciphertext.size < NONCE + 16) throw CryptoException("attachment too short")
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ciphertext, 0, NONCE))
                doFinal(ciphertext, NONCE, ciphertext.size - NONCE)
            }
        } catch (e: GeneralSecurityException) {
            throw CryptoException("attachment authentication failed", e)
        }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
}
