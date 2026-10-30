package app.novachat.data

import java.net.URLEncoder
import java.util.Locale

/** "📍 Home" or "📍 Location", for the chat list and notifications. */
fun locationLabel(place: SharedLocation): String = "📍 " + (place.name?.takeIf { it.isNotBlank() } ?: "Location")

/** A geo: link that opens the place in whichever maps app the phone has, with a pin on it. */
fun geoUri(place: SharedLocation): String {
    val lat = coordinate(place.lat)
    val lon = coordinate(place.lon)
    val label = place.name?.takeIf { it.isNotBlank() }?.let { "(" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") + ")" }.orEmpty()
    return "geo:$lat,$lon?q=$lat,$lon$label"
}

/** "23.81030° N, 90.41250° E" */
fun coordinatesText(place: SharedLocation): String {
    val ns = if (place.lat >= 0) "N" else "S"
    val ew = if (place.lon >= 0) "E" else "W"
    return "${coordinate(Math.abs(place.lat))}° $ns, ${coordinate(Math.abs(place.lon))}° $ew"
}

/** "Accurate to 12 m", or to the nearest 0.1 km for rough fixes. */
fun accuracyText(meters: Int?): String? = when {
    meters == null || meters <= 0 -> null
    meters < 1000 -> "Accurate to $meters m"
    else -> "Accurate to " + String.format(Locale.US, "%.1f", meters / 1000.0) + " km"
}

/** Whether a received location is a real place on Earth, not junk from a broken sender. */
fun isValidPlace(place: SharedLocation): Boolean =
    place.lat in -90.0..90.0 && place.lon in -180.0..180.0 && !place.lat.isNaN() && !place.lon.isNaN()

// Five decimals is about a metre: as precise as a phone fix gets.
private fun coordinate(v: Double) = String.format(Locale.US, "%.5f", v)
