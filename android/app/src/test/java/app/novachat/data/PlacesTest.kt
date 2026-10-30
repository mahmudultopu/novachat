package app.novachat.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacesTest {
    private val dhaka = SharedLocation(23.8103, 90.4125, accuracyM = 12)

    @Test
    fun geoLinksPinThePlace() {
        assertEquals("geo:23.81030,90.41250?q=23.81030,90.41250", geoUri(dhaka))
        assertEquals("geo:-33.86880,151.20930?q=-33.86880,151.20930(Opera%20House)", geoUri(SharedLocation(-33.8688, 151.2093, name = "Opera House")))
    }

    @Test
    fun coordinatesAndAccuracyReadWell() {
        assertEquals("23.81030° N, 90.41250° E", coordinatesText(dhaka))
        assertEquals("40.71280° N, 74.00600° W", coordinatesText(SharedLocation(40.7128, -74.006)))
        assertEquals("Accurate to 12 m", accuracyText(12))
        assertEquals("Accurate to 1.5 km", accuracyText(1500))
        assertNull(accuracyText(null))
    }

    @Test
    fun labelsAndValidation() {
        assertEquals("📍 Location", locationLabel(dhaka))
        assertEquals("📍 Home", locationLabel(dhaka.copy(name = "Home")))
        assertTrue(isValidPlace(dhaka))
        assertFalse(isValidPlace(SharedLocation(91.0, 0.0)))
        assertFalse(isValidPlace(SharedLocation(Double.NaN, 0.0)))
    }

    @Test
    fun locationSurvivesTheWire() {
        val content = MessageContent(location = dhaka.copy(name = "Office"))
        val back = NovaJson.decodeFromString<MessageContent>(NovaJson.encodeToString(MessageContent.serializer(), content))
        assertEquals(content.location, back.location)
        assertFalse(back.isControl)
    }
}
