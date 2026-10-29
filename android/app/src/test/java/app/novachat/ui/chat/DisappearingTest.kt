package app.novachat.ui.chat

import app.novachat.data.GroupEvent
import app.novachat.data.MessageContent
import app.novachat.data.NovaJson
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DisappearingTest {
    @Test
    fun timerChangesAreShownAsSentences() {
        val on = GroupEvent(event = "timer", actor = "me", seconds = 7 * 24 * 3600)
        assertEquals(
            "You turned on disappearing messages. New messages will disappear after 7 days.",
            groupEventText(on, "me", emptyMap()),
        )
        assertEquals("You turned off disappearing messages", groupEventText(on.copy(seconds = 0), "me", emptyMap()))
    }

    @Test
    fun labelsReadNaturally() {
        assertEquals(listOf("Off", "24 hours", "7 days", "90 days"), DisappearOptions.map(::disappearLabel))
    }

    @Test
    fun timerAndExpiryTravelInsideTheMessage() {
        val json = NovaJson.encodeToString(MessageContent(text = "hi", expireSec = 86400))
        assertEquals("""{"text":"hi","expire_s":86400}""", json)
        val timer = NovaJson.decodeFromString<MessageContent>("""{"timer_s":0}""")
        assertEquals(0, timer.timerSec)
        // A timer change is a visible line in the chat, not a hidden control message.
        assertFalse(timer.isControl)
    }
}
