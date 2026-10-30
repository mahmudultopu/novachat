package app.novachat.ui.chats

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.R
import app.novachat.data.ApiException
import app.novachat.data.Contact
import app.novachat.ui.appContainer
import app.novachat.ui.common.Avatar
import kotlinx.coroutines.launch

/** The link friends get in an invite, to download the app. */
const val APP_DOWNLOAD_LINK = "https://github.com/mahmudultopu/novachat/releases/download/latest/novachat.apk"

/** Start a chat: contacts on NovaChat, a new group, a phone number, or invite a friend. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(onBack: () -> Unit, openChat: (String) -> Unit, openNewGroup: () -> Unit, openJoin: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts by container.chats.contacts.collectAsStateWithLifecycle(initialValue = emptyList())
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
    }
    var syncing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var byNumber by remember { mutableStateOf(false) }

    fun sync() {
        if (syncing) return
        syncing = true
        error = null
        scope.launch {
            try {
                container.chats.syncContacts(container.addressBook.read())
            } catch (e: ApiException) {
                error = e.message
            } catch (e: SecurityException) {
                allowed = false
            }
            syncing = false
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
    }
    // Refresh every time the screen opens, so new contacts show up.
    LaunchedEffect(allowed) { if (allowed) sync() }

    val matching = contacts.filter { contactMatches(it, query) }
    val onNova = matching.filter { it.user != null }
    val others = matching.filter { it.user == null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("New chat")
                        if (allowed && contacts.isNotEmpty()) {
                            Text(
                                "${contacts.count { it.user != null }} contacts on NovaChat",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (allowed) IconButton(onClick = { sync() }, enabled = !syncing) { Icon(Icons.Filled.Refresh, "Refresh contacts") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "search") {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search name or number") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (query.isBlank()) {
                    item(key = "group") {
                        ActionRow(painterResource(R.drawable.ic_group), "New group", onClick = openNewGroup)
                    }
                    item(key = "join") {
                        ActionRow(painterResource(R.drawable.ic_link), "Join a group with a link", onClick = openJoin)
                    }
                    item(key = "number") {
                        ActionRow(rememberVectorPainter(Icons.Filled.Person), "Message a phone number") { byNumber = true }
                    }
                }
                if (!allowed) {
                    item(key = "allow") {
                        AllowContactsCard { permission.launch(Manifest.permission.READ_CONTACTS) }
                    }
                }
                error?.let { message ->
                    item(key = "error") {
                        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    }
                }
                if (onNova.isNotEmpty()) {
                    item(key = "on-header") { Header("On NovaChat") }
                    items(onNova, key = { "on-" + it.phone }) { c ->
                        val user = c.user!!
                        ContactRow(c, subtitle = user.about.ifBlank { c.phone }) { openChat(user.id) }
                    }
                }
                if (others.isNotEmpty()) {
                    item(key = "invite-header") { Header("Invite to NovaChat") }
                    items(others, key = { "invite-" + it.phone }) { c ->
                        ContactRow(c, subtitle = c.phone, trailing = "Invite") {
                            val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + c.phone))
                                .putExtra("sms_body", inviteText())
                            runCatching { context.startActivity(sms) }.onFailure {
                                val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, inviteText())
                                context.startActivity(Intent.createChooser(share, "Invite with"))
                            }
                        }
                    }
                }
                if (allowed && !syncing && contacts.isNotEmpty() && matching.isEmpty()) {
                    item(key = "none") {
                        Text(
                            "No contacts match \"${query.trim()}\"",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }

    if (byNumber) {
        PhoneNumberDialog(onDismiss = { byNumber = false }, onFound = { id ->
            byNumber = false
            openChat(id)
        })
    }
}

/** Matches a contact by name, or by digits anywhere in the number. */
fun contactMatches(c: Contact, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    if (c.name.contains(q, ignoreCase = true) || c.user?.name?.contains(q, ignoreCase = true) == true) return true
    val digits = q.filter(Char::isDigit)
    return digits.length >= 3 && c.phone.contains(digits)
}

fun inviteText() = "Let's chat on NovaChat! It's private and end-to-end encrypted. Get it here: $APP_DOWNLOAD_LINK"

@Composable
private fun AllowContactsCard(onAllow: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Find your friends", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(4.dp))
            Text(
                "Allow NovaChat to read your contacts to see who's already here. Only phone numbers are checked " +
                    "with the server, and it doesn't keep them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAllow) { Text("Allow contacts") }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ActionRow(icon: Painter, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ContactRow(c: Contact, subtitle: String, trailing: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.name, c.user?.id ?: c.phone, 48.dp, c.user?.avatarId)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun PhoneNumberDialog(onDismiss: () -> Unit, onFound: (String) -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("+") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message a phone number") },
        text = {
            Column {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it; error = null },
                    label = { Text("Phone number with country code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    isError = error != null,
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && phone.count(Char::isDigit) >= 6,
                onClick = {
                    loading = true
                    scope.launch {
                        try {
                            onFound(container.chats.findByPhone(phone).id)
                        } catch (e: ApiException) {
                            error = e.message
                        }
                        loading = false
                    }
                },
            ) { Text("Start chat") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
