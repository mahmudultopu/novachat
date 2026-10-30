package app.novachat.ui.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.novachat.R
import app.novachat.data.CallLog
import app.novachat.data.ChatMessage
import app.novachat.ui.appContainer
import app.novachat.ui.chat.formatDuration
import app.novachat.ui.common.formatTime

internal fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Returns a function that starts a call, asking for the microphone (and the
 * camera, for video) first. Without the camera a video call starts as voice.
 */
@Composable
fun rememberCallStarter(): (peerId: String, video: Boolean) -> Unit {
    val context = LocalContext.current
    val calls = appContainer().calls
    var pending by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val (peerId, video) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (context.has(Manifest.permission.RECORD_AUDIO)) {
            calls.startCall(peerId, video && context.has(Manifest.permission.CAMERA))
        } else {
            Toast.makeText(context, "NovaChat needs the microphone for calls", Toast.LENGTH_SHORT).show()
        }
    }
    return { peerId, video ->
        val needed = listOfNotNull(Manifest.permission.RECORD_AUDIO, if (video) Manifest.permission.CAMERA else null)
            .filterNot { context.has(it) }
        if (needed.isEmpty()) {
            calls.startCall(peerId, video)
        } else {
            pending = peerId to video
            ask.launch(needed.toTypedArray())
        }
    }
}

/** "Voice call · 2:31", "Missed video call" and so on, from the reader's side. */
fun callLogText(log: CallLog, outgoing: Boolean): String {
    val kind = (if (log.group) "Group " else "") + if (log.group) {
        if (log.video) "video call" else "voice call"
    } else {
        if (log.video) "Video call" else "Voice call"
    }
    return when {
        log.outcome == "answered" -> "$kind · ${formatDuration(log.durationSec * 1000L)}"
        !outgoing -> "Missed ${kind.lowercase()}"
        log.outcome == "declined" -> "$kind · Declined"
        log.outcome == "busy" -> "$kind · Busy"
        else -> "$kind · No answer"
    }
}

fun callIcon(log: CallLog, outgoing: Boolean): Int = when {
    outgoing -> R.drawable.ic_call_made
    log.missed -> R.drawable.ic_call_missed
    else -> R.drawable.ic_call_received
}

/** A finished call in the chat. Tap it to call back. */
@Composable
fun CallLogRow(m: ChatMessage, log: CallLog, mine: Boolean, onCallBack: () -> Unit) {
    val missed = log.missed && !mine
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            onClick = onCallBack,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (missed) Color(0x1FE5484D) else MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        painterResource(if (log.video) R.drawable.ic_videocam else R.drawable.ic_call),
                        null,
                        tint = if (missed) Color(0xFFE5484D) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(8.dp).size(18.dp),
                    )
                }
                Column {
                    Text(callLogText(log, mine), style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(callIcon(log, mine)),
                            null,
                            tint = if (missed) Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            " " + formatTime(m.createdAtMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
