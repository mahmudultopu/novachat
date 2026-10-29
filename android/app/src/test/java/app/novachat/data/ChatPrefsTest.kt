package app.novachat.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatPrefsTest {
    @Test
    fun muteLastsUntilItsEndTime() {
        val now = System.currentTimeMillis()
        assertFalse(ChatPrefs().muted)
        assertTrue(ChatPrefs(mutedUntilMillis = now + 60_000).muted)
        assertTrue(ChatPrefs(mutedUntilMillis = Long.MAX_VALUE).muted)
        assertFalse(ChatPrefs(mutedUntilMillis = now - 1).muted)
    }
}
