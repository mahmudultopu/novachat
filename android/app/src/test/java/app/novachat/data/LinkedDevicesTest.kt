package app.novachat.data

import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LinkedDevicesTest {
    private fun key(fill: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { fill.toByte() })

    @Test
    fun safetyNumberKeyIgnoresDeviceOrderButNotDevices() {
        val phone = key(1)
        val tablet = key(2)
        assertContentEquals(combinedIdentity(listOf(phone, tablet)), combinedIdentity(listOf(tablet, phone)))
        assertEquals(64, combinedIdentity(listOf(phone, tablet)).size)
        assertFalse(combinedIdentity(listOf(phone)).contentEquals(combinedIdentity(listOf(phone, tablet))))
    }

    @Test
    fun linkCodesKeepOnlyEightDigits() {
        assertEquals("12345678", linkCodeDigits("1234-5678"))
        assertEquals("12345678", linkCodeDigits(" 1234 5678 9"))
        assertEquals("12", linkCodeDigits("1a2"))
    }

    @Test
    fun lastActiveReadsLikeAPerson() {
        val now = Instant.parse("2026-10-30T12:00:00Z")
        assertEquals("Active now", lastActiveText("2026-10-30T11:59:30Z", now))
        assertEquals("Active 5 min ago", lastActiveText("2026-10-30T11:55:00Z", now))
        assertEquals("Active 3 h ago", lastActiveText("2026-10-30T09:00:00+00:00", now))
        assertEquals("Active yesterday", lastActiveText("2026-10-29T10:00:00Z", now))
        assertEquals("Active 4 days ago", lastActiveText("2026-10-26T12:00:00Z", now))
        assertEquals("", lastActiveText("not a time", now))
    }
}
