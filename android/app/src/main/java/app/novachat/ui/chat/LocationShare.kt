package app.novachat.ui.chat

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.CancellationSignal
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.novachat.R
import app.novachat.data.SharedLocation
import app.novachat.data.accuracyText
import app.novachat.data.coordinatesText
import app.novachat.data.geoUri
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Where the phone is: the first fresh fix from GPS or the network within
 * [timeoutMs], otherwise the newest last-known one. Null when location is
 * off or nothing is known. Needs a location permission.
 */
@SuppressLint("MissingPermission")
suspend fun currentLocation(context: Context, timeoutMs: Long = 20_000L): Location? {
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    if (providers.isEmpty()) return null
    val fresh = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<Location?> { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            var waiting = providers.size
            for (p in providers) {
                LocationManagerCompat.getCurrentLocation(lm, p, signal, ContextCompat.getMainExecutor(context)) { loc ->
                    waiting--
                    if (!cont.isActive) return@getCurrentLocation
                    if (loc != null) {
                        signal.cancel()
                        cont.resume(loc)
                    } else if (waiting == 0) {
                        cont.resume(null)
                    }
                }
            }
        }
    }
    return fresh ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
}

/** Finds where you are and lets you send it, with an optional name such as "Home". */
@Composable
fun SendLocationDialog(onDismiss: () -> Unit, onSend: (SharedLocation) -> Unit) {
    val context = LocalContext.current
    var place by remember { mutableStateOf<SharedLocation?>(null) }
    var failed by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        val loc = currentLocation(context)
        if (loc == null) {
            failed = true
        } else {
            place = SharedLocation(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toInt() else null)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send your location") },
        text = {
            Column {
                val p = place
                when {
                    p != null -> {
                        MapTile(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp)), MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(10.dp))
                        Text(coordinatesText(p), style = MaterialTheme.typography.bodyMedium)
                        accuracyText(p.accuracyM)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(60) },
                            label = { Text("Name this place (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    failed -> Text(
                        "Couldn't find where you are. Turn on location in your phone's settings, then try again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Finding your location…")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Only the people in this chat can see it. It's end-to-end encrypted like your messages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            val p = place
            when {
                p != null -> TextButton(onClick = { onSend(p.copy(name = name.trim().ifEmpty { null })) }) { Text("Send") }
                failed -> TextButton(onClick = { attempt++ }) { Text("Try again") }
                else -> TextButton(onClick = {}, enabled = false) { Text("Send") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A shared location in a bubble: a little map with a pin, the place, and a button to open it in maps. */
@Composable
fun LocationCard(place: SharedLocation, fg: Color) {
    val context = LocalContext.current
    val open = {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geoUri(place))))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No maps app on this phone", Toast.LENGTH_SHORT).show()
        }
    }
    Column(Modifier.widthIn(min = 220.dp, max = 260.dp)) {
        MapTile(
            Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = open),
            MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            place.name?.takeIf { it.isNotBlank() } ?: "Location",
            color = fg,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(coordinatesText(place), color = fg.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
        accuracyText(place.accuracyM)?.let {
            Text(it, color = fg.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = open, modifier = Modifier.padding(top = 2.dp)) { Text("Open in maps", color = fg) }
    }
}

/**
 * A drawn stand-in for a map: soft blocks, a few streets and a pin. Real map
 * tiles would tell a map company where you are, so the map app the phone
 * already has opens the place instead.
 */
@Composable
private fun MapTile(modifier: Modifier, accent: Color) {
    Box(modifier.background(accent.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val street = accent.copy(alpha = 0.22f)
            val w = size.width
            val h = size.height
            val thick = 6.dp.toPx()
            val thin = 3.dp.toPx()
            drawLine(street, Offset(0f, h * 0.35f), Offset(w, h * 0.55f), thick)
            drawLine(street, Offset(w * 0.3f, 0f), Offset(w * 0.42f, h), thick)
            drawLine(street, Offset(w * 0.7f, 0f), Offset(w * 0.62f, h), thin)
            drawLine(street, Offset(0f, h * 0.8f), Offset(w, h * 0.72f), thin)
            drawCircle(accent.copy(alpha = 0.18f), radius = 26.dp.toPx(), center = Offset(w / 2, h / 2))
        }
        Icon(
            painterResource(R.drawable.ic_location),
            null,
            tint = accent,
            modifier = Modifier.size(36.dp).padding(bottom = 6.dp),
        )
    }
}
