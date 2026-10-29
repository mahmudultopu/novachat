package app.novachat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.R
import app.novachat.data.ChatPrefs
import app.novachat.ui.appContainer

private const val DAY = 24 * 60 * 60

/** The times a chat's messages can disappear after, in seconds; 0 is off. */
val DisappearOptions = listOf(0, DAY, 7 * DAY, 90 * DAY)

/** "24 hours", "7 days", "90 days", or "Off". */
fun disappearLabel(seconds: Int): String = when {
    seconds <= 0 -> "Off"
    seconds % DAY != 0 -> "${seconds / 3600} hours"
    seconds == DAY -> "24 hours"
    else -> "${seconds / DAY} days"
}

/**
 * The "Disappearing messages" row on a contact's or group's info screen.
 * [enabled] is false when the user may not change it, such as in a group
 * where only admins edit the info.
 */
@Composable
fun DisappearingCard(peerId: String, enabled: Boolean) {
    val repo = appContainer().chats
    val prefs by remember(peerId) { repo.chatPrefs(peerId) }.collectAsStateWithLifecycle(initialValue = ChatPrefs())
    var choosing by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.clickable(enabled = enabled) { choosing = true }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_clock), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Disappearing messages", style = MaterialTheme.typography.titleSmall)
                Text(
                    disappearLabel(prefs.disappearSec),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (choosing) {
        DisappearingDialog(
            current = prefs.disappearSec,
            onDismiss = { choosing = false },
            onPick = { sec ->
                choosing = false
                if (sec != prefs.disappearSec) repo.setDisappearing(peerId, sec)
            },
        )
    }
}

@Composable
private fun DisappearingDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var picked by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disappearing messages") },
        text = {
            Column {
                Text(
                    "New messages in this chat will disappear for everyone after the time you pick. " +
                        "Messages sent before stay. Anyone can still take a screenshot.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                DisappearOptions.forEach { sec ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = picked == sec, onClick = { picked = sec }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = picked == sec, onClick = { picked = sec })
                        Spacer(Modifier.width(8.dp))
                        Text(disappearLabel(sec), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(picked) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
