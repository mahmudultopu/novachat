package app.novachat.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PrivacySettingsTest {
    @Test
    fun readsTheServersSettings() {
        val p = NovaJson.decodeFromString<PrivacySettings>(
            """{"last_seen":"nobody","photo":"chats","about":"everyone","read_receipts":false}""",
        )
        assertEquals("nobody", p.lastSeen)
        assertEquals("chats", p.photo)
        assertFalse(p.readReceipts)
    }
}
