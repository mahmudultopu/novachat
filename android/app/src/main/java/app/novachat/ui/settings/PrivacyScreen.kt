package app.novachat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.novachat.data.ApiException
import app.novachat.data.PrivacySettings
import app.novachat.data.User
import app.novachat.ui.appContainer
import app.novachat.ui.common.Avatar
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val Levels = listOf("everyone" to "Everyone", "chats" to "People I've messaged", "nobody" to "Nobody")

private fun levelLabel(level: String) = Levels.firstOrNull { it.first == level }?.second ?: "Everyone"

/** Who sees your last seen, photo and about; read receipts; and blocked people. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val repo = appContainer().chats
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<PrivacySettings?>(null) }
    var blocked by remember { mutableStateOf<List<User>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    // Which setting's choice dialog is open: "last_seen", "photo" or "about".
    var choosing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            settings = repo.privacy()
            blocked = repo.blockedPeople()
        } catch (e: ApiException) {
            error = e.message
        }
    }

    fun change(changes: JsonObject) {
        scope.launch {
            try {
                settings = repo.updatePrivacy(changes)
                error = null
            } catch (e: ApiException) {
                error = e.message
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
            }
            val s = settings
            if (s == null) {
                if (error == null) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }
            Section("Who can see my personal info") {
                ChoiceRow("Last seen and online", levelLabel(s.lastSeen)) { choosing = "last_seen" }
                ChoiceRow("Profile photo", levelLabel(s.photo)) { choosing = "photo" }
                ChoiceRow("About", levelLabel(s.about)) { choosing = "about" }
                Text(
                    "If you hide your last seen, you can still see other people's.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Section(null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Read receipts", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "When off, people won't see blue ticks for messages you read. Groups always show them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = s.readReceipts, onCheckedChange = { on -> change(buildJsonObject { put("read_receipts", on) }) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Section("Blocked contacts") {
                if (blocked.isEmpty()) {
                    Text(
                        "Nobody. To block someone, open their info from a chat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                blocked.forEach { u ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(u.displayName, u.id, 40.dp, u.avatarId)
                        Spacer(Modifier.width(12.dp))
                        Text(u.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    repo.unblock(u.id)
                                    blocked = blocked - u
                                } catch (e: ApiException) {
                                    error = e.message
                                }
                            }
                        }) { Text("Unblock") }
                    }
                }
            }
        }
    }

    choosing?.let { key ->
        val s = settings ?: return@let
        val current = when (key) {
            "last_seen" -> s.lastSeen
            "photo" -> s.photo
            else -> s.about
        }
        val title = when (key) {
            "last_seen" -> "Last seen and online"
            "photo" -> "Profile photo"
            else -> "About"
        }
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text(title) },
            text = {
                Column {
                    Levels.forEach { (level, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = current == level, onClick = {
                                choosing = null
                                change(buildJsonObject { put(key, level) })
                            }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = current == level, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String?, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            title?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
            }
            content()
        }
    }
}

@Composable
private fun ChoiceRow(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
