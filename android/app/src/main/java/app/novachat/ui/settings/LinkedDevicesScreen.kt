package app.novachat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.novachat.R
import app.novachat.data.ApiException
import app.novachat.data.LinkedDevice
import app.novachat.data.lastActiveText
import app.novachat.data.linkCodeDigits
import app.novachat.ui.appContainer
import kotlinx.coroutines.launch

/** Settings → Linked devices: your phone, the devices linked to it, and linking a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedDevicesScreen(onBack: () -> Unit) {
    val repo = appContainer().chats
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<LinkedDevice>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var linking by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<LinkedDevice?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        try {
            devices = repo.linkedDevices()
            error = null
        } catch (e: ApiException) {
            error = e.message
        }
    }
    val thisIsLinked = devices?.firstOrNull { it.current }?.linked == true
    val linkedCount = devices?.count { it.linked } ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Linked devices") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "Use your account on up to $MAX_LINKED more devices, like a tablet or a second phone. " +
                    "Each device has its own encryption keys, so your messages stay end-to-end encrypted on every one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (!thisIsLinked && devices != null) {
                Button(onClick = { linking = true }, enabled = linkedCount < MAX_LINKED, modifier = Modifier.fillMaxWidth()) {
                    Text(if (linkedCount < MAX_LINKED) "Link a device" else "$MAX_LINKED devices linked: remove one to add another")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "On the new device, install NovaChat and tap \"Link this device\". Then type the code it shows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }
            notice?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
            }
            val list = devices
            if (list == null && error == null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
            list?.forEach { d ->
                DeviceRow(d, canRemove = d.linked && !d.current, onRemove = { removing = d })
                Spacer(Modifier.height(10.dp))
            }
            if (thisIsLinked) {
                Text(
                    "This device is linked to your phone. To stop using it, log out in Settings, or remove it from your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (linking) {
        LinkDialog(
            onDismiss = { linking = false },
            onLink = { code ->
                val linked = repo.approveLink(code)
                linking = false
                notice = "${linked.name.ifBlank { "The device" }} is linked. It gets your new messages from now on."
                reload++
            },
        )
    }
    removing?.let { d ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${d.name.ifBlank { "this device" }}?") },
            text = { Text("It will be logged out and stop getting your messages.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch {
                        try {
                            repo.removeDevice(d.id)
                            notice = "${d.name.ifBlank { "The device" }} was removed."
                        } catch (e: ApiException) {
                            error = e.message
                        }
                        reload++
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeviceRow(d: LinkedDevice, canRemove: Boolean, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_devices), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name.ifBlank { "Unnamed device" }, style = MaterialTheme.typography.titleSmall)
                val kind = if (d.linked) "Linked device" else "Main phone"
                val status = if (d.current) "This device" else lastActiveText(d.lastSeenAt)
                Text(
                    listOf(kind, status).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canRemove) {
                TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit, onLink: suspend (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Link a device") },
        text = {
            Column {
                Text("Type the 8-digit code the new device shows.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = linkCodeDigits(it); error = null },
                    placeholder = { Text("1234 5678") },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 22.sp, letterSpacing = 4.sp, textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Only link devices you own. A linked device can read all your new messages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.length == 8 && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            onLink(code)
                        } catch (e: ApiException) {
                            error = e.message
                        }
                        busy = false
                    }
                },
            ) { Text(if (busy) "Linking…" else "Link") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** Matches the server's limit on linked devices per phone. */
private const val MAX_LINKED = 4
