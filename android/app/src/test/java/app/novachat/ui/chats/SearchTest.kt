package app.novachat.ui.chats

import app.novachat.data.ChatMessage
import app.novachat.ui.chat.searchMatches
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTest {
    private fun msg(id: String, body: String, deleted: Boolean = false) =
        ChatMessage(clientId = id, id = null, senderId = "a", recipientId = "b", body = body, createdAtMillis = 0, deleted = deleted)

    @Test
    fun chatSearchFindsTextNewestFirstAndSkipsDeleted() {
        val messages = listOf(msg("1", "Lunch at noon?"), msg("2", "no LUNCH today", deleted = true), msg("3", "lunch is on me"))
        assertEquals(listOf("3", "1"), searchMatches(messages, " lunch ").map { it.clientId })
        assertEquals(emptyList(), searchMatches(messages, "  "))
    }

    @Test
    fun snippetsStartNearTheMatch() {
        assertEquals("short text", snippet("short text", "text"))
        val long = "x".repeat(100) + "needle here"
        val s = snippet(long, "needle")
        assertEquals('…', s.first())
        assertEquals(true, s.contains("needle here"))
    }
}
