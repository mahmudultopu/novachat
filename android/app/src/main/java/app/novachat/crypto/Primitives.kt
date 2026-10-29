package app.novachat.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when a message cannot be decrypted or a key fails verification. */
class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A Curve25519 key pair used for Diffie-Hellman (X25519). */
class DhKeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {
    fun agree(theirPublic: ByteArray): ByteArray = try {
        X25519.computeSharedSecret(privateKey, theirPublic)
    } catch (e: GeneralSecurityException) {
        throw CryptoException("bad public key", e)
    }

    companion object {
        fun generate(): DhKeyPair {
            val priv = X25519.generatePrivateKey()
            return DhKeyPair(priv, X25519.publicFromPrivate(priv))
        }

        fun fromPrivate(priv: ByteArray) = DhKeyPair(priv, X25519.publicFromPrivate(priv))
    }
}

/** An Ed25519 key pair that signs prekeys. [privateKey] is the 32-byte seed. */
class SigningKeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {
    fun sign(data: ByteArray): ByteArray = Ed25519Sign(privateKey).sign(data)

    companion object {
        fun generate(): SigningKeyPair {
            val kp = Ed25519Sign.KeyPair.newKeyPair()
            return SigningKeyPair(kp.privateKey, kp.publicKey)
        }

        fun verify(publicKey: ByteArray, signature: ByteArray, data: ByteArray): Boolean = try {
            Ed25519Verify(publicKey).verify(signature, data)
            true
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}

internal object Crypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, size: Int): ByteArray =
        Hkdf.computeHkdf("HMACSHA256", ikm, salt, info.toByteArray(), size)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    fun sha512(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").run {
            parts.forEach(::update)
            digest()
        }

    /** AES-256-GCM with a key and nonce derived from one message key. */
    fun seal(messageKey: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray {
        val (key, nonce) = expand(messageKey)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(ad)
            doFinal(plaintext)
        }
    }

    fun open(messageKey: ByteArray, ciphertext: ByteArray, ad: ByteArray): ByteArray {
        val (key, nonce) = expand(messageKey)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(ad)
                doFinal(ciphertext)
            }
        } catch (e: GeneralSecurityException) {
            throw CryptoException("message authentication failed", e)
        }
    }

    private fun expand(messageKey: ByteArray): Pair<ByteArray, ByteArray> {
        val out = hkdf(messageKey, ByteArray(32), "NovaChat message keys", 44)
        return out.copyOfRange(0, 32) to out.copyOfRange(32, 44)
    }
}

internal fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)

internal fun String.unb64(): ByteArray = try {
    Base64.getDecoder().decode(this)
} catch (e: IllegalArgumentException) {
    throw CryptoException("bad base64", e)
}
