package app.novachat.data

import app.novachat.data.local.ChatPrefsEntity
import app.novachat.data.local.ContactEntity
import app.novachat.data.local.GroupEntity
import app.novachat.data.local.MessageEntity
import app.novachat.data.local.MessageKey
import app.novachat.data.local.UserEntity
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Everything a chat backup holds. Keys and media files stay out of it. */
@Serializable
data class BackupData(
    val version: Int = 1,
    val userId: String,
    val createdAtMillis: Long,
    val messages: List<MessageEntity>,
    val users: List<UserEntity> = emptyList(),
    val groups: List<GroupEntity> = emptyList(),
    val chatPrefs: List<ChatPrefsEntity> = emptyList(),
    val contacts: List<ContactEntity> = emptyList(),
)

class BackupException(message: String) : Exception(message)

const val MIN_BACKUP_PASSWORD = 6

/** Checks a new backup password typed twice; returns what's wrong, or null when it's good. */
fun backupPasswordProblem(first: String, second: String): String? = when {
    first.length < MIN_BACKUP_PASSWORD -> "Use at least $MIN_BACKUP_PASSWORD characters"
    first != second -> "The passwords don't match"
    else -> null
}

/** The message rows a backup should copy onto this phone: never one the phone can already read. */
fun messagesToRestore(backup: List<MessageEntity>, readableHere: Set<MessageKey>): List<MessageEntity> =
    backup.filter { it.id != null && MessageKey(it.senderId, it.clientId) !in readableHere }

/**
 * A backup file is "NOVABAK1", a random salt and IV, then the gzipped JSON
 * sealed with AES-GCM under a key stretched from the password. Without the
 * password the file is unreadable, wherever it is kept.
 */
object BackupCrypto {
    private val MAGIC = "NOVABAK1".toByteArray()
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val ITERATIONS = 150_000

    fun encrypt(plain: ByteArray, password: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        return MAGIC + salt + iv + cipher.doFinal(gzip(plain))
    }

    fun decrypt(file: ByteArray, password: String): ByteArray {
        val head = MAGIC.size + SALT_BYTES + IV_BYTES
        if (file.size <= head || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException("This isn't a NovaChat backup file")
        }
        val salt = file.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val iv = file.copyOfRange(MAGIC.size + SALT_BYTES, head)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        val sealed = try {
            cipher.doFinal(file, head, file.size - head)
        } catch (e: GeneralSecurityException) {
            throw BackupException("Wrong password, or the file is damaged")
        }
        return gunzip(sealed)
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}
