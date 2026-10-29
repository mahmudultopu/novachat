package app.novachat.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class AttachmentCipherTest {
    @Test
    fun roundTrip() {
        val photo = Random.nextBytes(300_000)
        val enc = AttachmentCipher.encrypt(photo)
        assertContentEquals(photo, AttachmentCipher.decrypt(enc.ciphertext, enc.key, enc.digest))
    }

    @Test
    fun wrongKeyOrSwappedFileIsRejected() {
        val enc = AttachmentCipher.encrypt("voice note".toByteArray())
        assertFailsWith<CryptoException> { AttachmentCipher.decrypt(enc.ciphertext, Random.nextBytes(32), enc.digest) }
        val other = AttachmentCipher.encrypt("another file".toByteArray())
        assertFailsWith<CryptoException> { AttachmentCipher.decrypt(other.ciphertext, enc.key, enc.digest) }
    }
}
