package app.novachat.data

import app.novachat.ui.chats.contactMatches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactsTest {
    @Test
    fun numbersWithACountryCodeAreCleanedUp() {
        assertEquals("+8801712345678", internationalNumber(" +880 1712-345678 "))
        assertEquals("+447700900123", internationalNumber("0044 7700 900123"))
        // Local numbers need the phone's country, so they're left to Android.
        assertNull(internationalNumber("01712-345678"))
        assertNull(internationalNumber("+12"))
    }

    @Test
    fun duplicateNumbersKeepTheFirstNameAndSortByName() {
        val book = uniqueContacts(
            listOf(PhoneContact("zara", "+1"), PhoneContact("Ali", "+2"), PhoneContact("Zara work", "+1")),
        )
        assertEquals(listOf("Ali", "zara"), book.map { it.name })
    }

    @Test
    fun searchMatchesNameOrDigits() {
        val c = Contact("+8801712345678", "Rina Akter", User("u1", "+8801712345678", name = "Rina"))
        assertTrue(contactMatches(c, ""))
        assertTrue(contactMatches(c, "akter"))
        assertTrue(contactMatches(c, "1712 345"))
        assertFalse(contactMatches(c, "17"))
        assertFalse(contactMatches(c, "karim"))
    }
}
