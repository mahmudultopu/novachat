package app.novachat.ui.groups

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.novachat.data.ApiException
import app.novachat.data.InvitePreview
import app.novachat.ui.appContainer
import app.novachat.ui.chat.inviteCodeFrom
import app.novachat.ui.common.Avatar
import kotlinx.coroutines.launch

/**
 * Join a group from an invite link: paste the link (or open one, which
 * fills it in), see which group it is, then join.
 */
@Composable
fun JoinGroupDialog(initialLink: String, onDismiss: () -> Unit, onJoined: (String) -> Unit) {
    val repo = appContainer().chats
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(initialLink) }
    var preview by remember { mutableStateOf<InvitePreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val code = inviteCodeFrom(link)

    // Look the link up as soon as it is complete.
    LaunchedEffect(code) {
        preview = null
        error = null
        if (code != null) {
            busy = true
            try {
                preview = repo.previewInvite(code)
            } catch (e: ApiException) {
                error = e.message
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a group") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val p = preview
                if (p == null) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        label = { Text("Invite link") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Avatar(p.name, p.id, 72.dp, p.avatarId)
                    Spacer(Modifier.height(12.dp))
                    Text(p.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(
                        "Group · ${p.memberCount} ${if (p.memberCount == 1) "member" else "members"}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (p.description.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(p.description, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 4)
                    }
                    if (p.isMember) {
                        Spacer(Modifier.height(8.dp))
                        Text("You're already in this group.", color = MaterialTheme.colorScheme.secondary)
                    }
                }
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            val p = preview
            TextButton(
                enabled = p != null && code != null && !busy,
                onClick = {
                    if (p == null || code == null) return@TextButton
                    if (p.isMember) {
                        onJoined(p.id)
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        try {
                            onJoined(repo.joinInvite(code))
                        } catch (e: ApiException) {
                            error = e.message
                        }
                        busy = false
                    }
                },
            ) { Text(if (p?.isMember == true) "Open" else "Join group") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
