package app.novachat.data

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageContentTest {
    private val ref = MessageRef(senderId = "alice", clientId = "c1")

    @Test
    fun plainMessagesStayCompact() {
        // Older versions only know text and attachment; new fields are left out when unused.
        assertEquals("""{"text":"hi"}""", NovaJson.encodeToString(MessageContent(text = "hi")))
        val old = NovaJson.decodeFromString<MessageContent>("""{"text":"hi"}""")
        assertFalse(old.isControl)
        assertFalse(old.forwarded)
    }

    @Test
    fun controlMessagesRoundTrip() {
        for (c in listOf(
            MessageContent(reaction = Reaction(ref, "👍")),
            MessageContent(edit = Edit(ref, "fixed typo")),
            MessageContent(delete = ref),
        )) {
            val back = NovaJson.decodeFromString<MessageContent>(NovaJson.encodeToString(c))
            assertEquals(c, back)
            assertTrue(back.isControl)
        }
    }

    @Test
    fun repliesAndForwardsAreNotControl() {
        val c = MessageContent(text = "yes", replyTo = ref, forwarded = true)
        val json = NovaJson.encodeToString(c)
        assertTrue("\"reply_to\":{\"sender_id\":\"alice\",\"client_id\":\"c1\"}" in json)
        assertFalse(NovaJson.decodeFromString<MessageContent>(json).isControl)
    }
}
