package app.novachat.data

import app.novachat.crypto.unb64
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * All of someone's device identity keys as one key for the safety number.
 * Sorted first, so every phone gets the same digits whatever order the
 * server lists the devices in. Linking or removing a device changes it.
 */
internal fun combinedIdentity(keysB64: List<String>): ByteArray =
    keysB64.sorted().fold(ByteArray(0)) { acc, k -> acc + k.unb64() }

/** The 8 digits of a link code, however it was typed ("1234 5678", "1234-5678"). */
internal fun linkCodeDigits(input: String): String = input.filter { it.isDigit() }.take(8)

/** "Active now", "Active 5 min ago", "Active 3 h ago" or "Active 2 days ago" for a device. */
internal fun lastActiveText(lastSeenAt: String, now: Instant = Instant.now()): String {
    val at = runCatching { OffsetDateTime.parse(lastSeenAt).toInstant() }.getOrNull() ?: return ""
    val ago = Duration.between(at, now)
    return when {
        ago.toMinutes() < 2 -> "Active now"
        ago.toHours() < 1 -> "Active ${ago.toMinutes()} min ago"
        ago.toDays() < 1 -> "Active ${ago.toHours()} h ago"
        ago.toDays() == 1L -> "Active yesterday"
        else -> "Active ${ago.toDays()} days ago"
    }
}
