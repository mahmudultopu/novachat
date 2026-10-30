package app.novachat.data

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContactCardTest {
    @Test
    fun cardsAreTidiedBeforeSending() {
        val card = cleanContact(
            SharedContact(
                "  Ana   Rahman ",
                listOf("+880 1712-345678", "+8801712345678", "", "12", "01812 345678", "+1 (415) 555-0100", "+44 20 7946 0000", "+91 98765 43210"),
            ),
        )!!
        assertEquals("Ana Rahman", card.name)
        // Duplicates, blanks and too-short numbers go; at most five stay.
        assertEquals(listOf("+880 1712-345678", "01812 345678", "+1 (415) 555-0100", "+44 20 7946 0000", "+91 98765 43210"), card.phones)
    }

    @Test
    fun junkCardsAreDropped() {
        assertNull(cleanContact(SharedContact("   ", emptyList())))
        assertNull(cleanContact(SharedContact("", listOf("abc"))))
        assertEquals(MAX_CARD_NAME, cleanContact(SharedContact("x".repeat(500)))!!.name.length)
        // No name: the number stands in for it.
        assertEquals("+8801712345678", cleanContact(SharedContact("", listOf("+8801712345678")))!!.name)
        assertEquals(listOf("+8801712345678"), cleanContact(SharedContact("Ben", listOf("+880<b>1712345678</b>")))!!.phones)
    }

    @Test
    fun initialsAndLabels() {
        assertEquals("AR", contactInitials("Ana Maria Rahman"))
        assertEquals("B", contactInitials("ben"))
        assertEquals("#", contactInitials("+8801712345678"))
        assertEquals("👤 Ana", contactLabel(SharedContact("Ana")))
    }

    @Test
    fun messageButtonUsesAnInternationalNumber() {
        assertEquals("+8801712345678", reachableNumber(SharedContact("Ana", listOf("01712 345678", "+880 1712-345678"))))
        assertNull(reachableNumber(SharedContact("Ana", listOf("01712 345678"))))
    }

    @Test
    fun cardsSurviveTheTrip() {
        val content = MessageContent(contact = SharedContact("Ana", listOf("+8801712345678")))
        val back = NovaJson.decodeFromString<MessageContent>(NovaJson.encodeToString(content))
        assertEquals(content.contact, back.contact)
    }
}
