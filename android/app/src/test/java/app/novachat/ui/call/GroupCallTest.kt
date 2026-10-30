package app.novachat.ui.call

import app.novachat.calls.CallMember
import app.novachat.calls.MemberPhase
import app.novachat.data.CallLog
import app.novachat.data.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupCallTest {
    @Test
    fun youComeFirstThenEveryoneWithTheirStatus() {
        val people = mapOf("b" to User("b", "+2", name = "Ben"), "c" to User("c", "+3", name = "Cleo"))
        val members = listOf(CallMember("b", MemberPhase.Connected, video = false), CallMember("c", MemberPhase.Invited, video = true))
        val tiles = groupCallTiles("a", User("a", "+1", name = "Ana"), members, people, emptyMap(), null, frontCamera = true)
        assertEquals(listOf("You", "Ben", "Cleo"), tiles.map { it.name })
        assertNull(tiles[1].status)
        assertEquals("Ringing…", tiles[2].status)
        // No video until their connection is up.
        assertNull(tiles[2].video)
    }

    @Test
    fun tilesFillRowsOfTwo() {
        assertEquals(listOf(1), gridRows(1))
        assertEquals(listOf(1, 1), gridRows(2))
        assertEquals(listOf(2, 1), gridRows(3))
        assertEquals(listOf(2, 2, 2), gridRows(6))
    }

    @Test
    fun groupCallsSaySoInTheChat() {
        assertEquals("Group video call · 2:31", callLogText(CallLog(video = true, outcome = "answered", durationSec = 151, group = true), outgoing = true))
        assertEquals("Missed group voice call", callLogText(CallLog(outcome = "missed", group = true), outgoing = false))
        assertEquals("Voice call · No answer", callLogText(CallLog(outcome = "missed"), outgoing = true))
    }
}
