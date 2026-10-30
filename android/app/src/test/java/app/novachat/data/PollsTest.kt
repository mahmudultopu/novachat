package app.novachat.data

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PollsTest {
    private val lunch = Poll("Lunch?", listOf("Rice", "Noodles", "Pizza"))
    private val snacks = lunch.copy(multi = true)

    @Test
    fun pollsAreTidiedAndJunkIsDropped() {
        val clean = cleanPoll(Poll("  Lunch? ", listOf(" Rice ", "", "Rice", "Noodles") + List(20) { "Option $it" }))!!
        assertEquals("Lunch?", clean.question)
        assertEquals(MAX_POLL_OPTIONS, clean.options.size)
        assertEquals(listOf("Rice", "Noodles"), clean.options.take(2))
        assertNull(cleanPoll(Poll("Lunch?", listOf("Rice", " Rice "))))
        assertNull(cleanPoll(Poll(" ", listOf("A", "B"))))
    }

    @Test
    fun theFormSaysWhatsMissing() {
        assertEquals("Ask a question", pollProblem("", listOf("A", "B")))
        assertEquals("Add at least 2 options", pollProblem("Q", listOf("A", " ")))
        assertEquals("Each option must be different", pollProblem("Q", listOf("A", "A ")))
        assertNull(pollProblem("Q", listOf("A", "B", "")))
    }

    @Test
    fun votesFitThePoll() {
        assertEquals(listOf(0), cleanChoices(lunch, listOf(0, 2)))
        assertEquals(listOf(0, 2), cleanChoices(snacks, listOf(2, 0, 2, 7, -1)))
        assertEquals(listOf(2), toggleChoice(lunch, listOf(0), 2))
        assertEquals(emptyList<Int>(), toggleChoice(lunch, listOf(0), 0))
        assertEquals(listOf(0, 2), toggleChoice(snacks, listOf(2), 0))
    }

    @Test
    fun countsArePerOption() {
        val votes = mapOf("ana" to listOf(0, 2), "ben" to listOf(2), "cy" to emptyList())
        assertEquals(listOf(1, 0, 2), tally(snacks, votes))
        assertEquals("2 votes", votersText(votes))
        assertEquals("No votes yet", votersText(emptyMap()))
        assertEquals("📊 Lunch?", pollLabel(lunch))
    }

    @Test
    fun votesAreControlMessages() {
        val vote = MessageContent(vote = PollVote(MessageRef("ana", "c1"), listOf(1)))
        assertTrue(vote.isControl)
        val back = NovaJson.decodeFromString<MessageContent>(NovaJson.encodeToString(MessageContent(poll = snacks)))
        assertEquals(snacks, back.poll)
    }
}
