package app.novachat.data

import app.novachat.data.local.ChatPrefsEntity
import app.novachat.data.local.MessageEntity
import app.novachat.data.local.MessageKey
import app.novachat.data.local.UserEntity
import app.novachat.ui.settings.backupFileName
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupTest {
    private fun msg(clientId: String, id: Long?, undecryptable: Boolean = false) = MessageEntity(
        senderId = "alice",
        clientId = clientId,
        id = id,
        recipientId = "bob",
        peerId = "bob",
        body = "hello $clientId",
        createdAtMillis = 1_000L,
        deliveredAt = null,
        readAt = null,
        failed = false,
        undecryptable = undecryptable,
    )

    @Test
    fun backupsOpenOnlyWithTheirPassword() {
        val plain = "chats".repeat(1000).toByteArray()
        val file = BackupCrypto.encrypt(plain, "correct horse")
        assertTrue(file.size < plain.size, "gzipped before sealing")
        assertContentEquals(plain, BackupCrypto.decrypt(file, "correct horse"))
        assertEquals("Wrong password, or the file is damaged", assertFailsWith<BackupException> { BackupCrypto.decrypt(file, "wrong") }.message)
        assertFailsWith<BackupException> { BackupCrypto.decrypt("not a backup".toByteArray(), "correct horse") }
        val tampered = file.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<BackupException> { BackupCrypto.decrypt(tampered, "correct horse") }
    }

    @Test
    fun backupDataSurvivesJson() {
        val data = BackupData(
            userId = "alice",
            createdAtMillis = 5L,
            messages = listOf(msg("a", 1).copy(reactions = """{"bob":"👍"}""", starred = true)),
            users = listOf(UserEntity("bob", "+15550001", "Bob", "hi")),
            chatPrefs = listOf(ChatPrefsEntity("bob", pinnedAtMillis = 9L, disappearSec = 3600)),
        )
        assertEquals(data, NovaJson.decodeFromString<BackupData>(NovaJson.encodeToString(BackupData.serializer(), data)))
    }

    @Test
    fun restoreNeverReplacesMessagesThisPhoneCanRead() {
        val backup = listOf(msg("readable", 1), msg("locked", 2), msg("missing", 3), msg("unsent", null))
        val restored = messagesToRestore(backup, setOf(MessageKey("alice", "readable")))
        assertEquals(listOf("locked", "missing"), restored.map { it.clientId })
    }

    @Test
    fun passwordsNeedSixCharactersTypedTwice() {
        assertNull(backupPasswordProblem("secret1", "secret1"))
        assertEquals("Use at least 6 characters", backupPasswordProblem("abc", "abc"))
        assertEquals("The passwords don't match", backupPasswordProblem("secret1", "secret2"))
    }

    @Test
    fun fileNamesCarryTheDate() {
        assertTrue(backupFileName(System.currentTimeMillis()).matches(Regex("""novachat-backup-\d{8}\.nbk""")))
    }
}
