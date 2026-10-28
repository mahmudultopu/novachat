package app.novachat.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import app.novachat.R
import app.novachat.data.DeliveryStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.absoluteValue

private val avatarPalette = listOf(
    Color(0xFF5B3DF5), Color(0xFF19A99B), Color(0xFFE2577B), Color(0xFFF29B38),
    Color(0xFF3C8DEB), Color(0xFF9A4DD8), Color(0xFF2FA35A), Color(0xFFD1494A),
)

/** A round avatar with the person's initials, coloured by their ID. */
@Composable
fun Avatar(name: String, id: String, size: Dp = 48.dp) {
    val initials = name.split(' ', '+').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "#" }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarPalette[id.hashCode().absoluteValue % avatarPalette.size]),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.36f).sp)
    }
}

/** Clock, one tick, two ticks, or two coloured ticks. */
@Composable
fun StatusTicks(status: DeliveryStatus, tint: Color, readTint: Color, size: Dp = 16.dp) {
    when (status) {
        DeliveryStatus.Pending -> Icon(painterResource(R.drawable.ic_clock), "Sending", Modifier.size(size * 0.85f), tint = tint)
        DeliveryStatus.Failed -> Icon(Icons.Filled.Warning, "Not sent", Modifier.size(size), tint = MaterialTheme.colorScheme.error)
        DeliveryStatus.Sent -> Icon(Icons.Filled.Done, "Sent", Modifier.size(size), tint = tint)
        DeliveryStatus.Delivered, DeliveryStatus.Read -> {
            val c = if (status == DeliveryStatus.Read) readTint else tint
            Box(Modifier.size(width = size * 1.35f, height = size)) {
                Icon(Icons.Filled.Done, null, Modifier.size(size), tint = c)
                Icon(
                    Icons.Filled.Done,
                    if (status == DeliveryStatus.Read) "Read" else "Delivered",
                    Modifier.size(size).offset(x = size * 0.35f),
                    tint = c,
                )
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)

fun formatTime(millis: Long): String =
    timeFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** "14:05" for today, "Yesterday", or a short date. */
fun formatListTime(millis: Long): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    return when (zoned.toLocalDate()) {
        today -> timeFormat.format(zoned)
        today.minusDays(1) -> "Yesterday"
        else -> dateFormat.format(zoned)
    }
}
