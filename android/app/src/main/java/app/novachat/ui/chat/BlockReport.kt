package app.novachat.ui.chat

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.R
import app.novachat.data.ApiException
import app.novachat.ui.appContainer
import kotlinx.coroutines.launch

private val ReportReasons = listOf("Spam", "Scam or fraud", "Harassment or bullying", "Pretending to be someone else", "Something else")

/** "Block" and "Report" on a contact's info screen. */
@Composable
fun BlockAndReport(peerId: String, name: String) {
    val repo = appContainer().chats
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val blocked by remember(peerId) { repo.isBlocked(peerId) }.collectAsStateWithLifecycle(initialValue = false)
    var confirmBlock by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }

    fun run(done: String, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
            } catch (e: ApiException) {
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    InfoCard {
        ActionLine(if (blocked) "Unblock $name" else "Block $name") {
            if (blocked) run("$name unblocked") { repo.unblock(peerId) } else confirmBlock = true
        }
        ActionLine("Report $name") { reporting = true }
    }

    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text("Block $name?") },
            text = {
                Text(
                    "They won't be able to call you or send you messages, and won't see your photo, about or last seen. " +
                        "They won't be told you blocked them.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = false
                    run("$name blocked") { repo.block(peerId) }
                }) { Text("Block", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text("Cancel") } },
        )
    }

    if (reporting) {
        var reason by remember { mutableStateOf(ReportReasons.first()) }
        var alsoBlock by remember { mutableStateOf(!blocked) }
        AlertDialog(
            onDismissRequest = { reporting = false },
            title = { Text("Report $name?") },
            text = {
                Column {
                    Text(
                        "Your messages stay end-to-end encrypted, so only the reason you pick is sent.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    ReportReasons.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = reason == r, onClick = { reason = r }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = reason == r, onClick = { reason = r })
                            Text(r, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (!blocked) {
                        Row(
                            Modifier.fillMaxWidth().clickable { alsoBlock = !alsoBlock },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = alsoBlock, onCheckedChange = { alsoBlock = it })
                            Text("Also block $name", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    reporting = false
                    val block = alsoBlock && !blocked
                    run(if (block) "Reported and blocked" else "Reported, thank you") { repo.report(peerId, reason, block) }
                }) { Text("Report", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { reporting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActionLine(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_block), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(14.dp))
        Text(label, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
    }
}
