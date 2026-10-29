package app.novachat.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.SessionState
import app.novachat.data.ApiException
import app.novachat.data.ConnectionState
import app.novachat.data.Conversation
import app.novachat.ui.appContainer
import app.novachat.ui.chat.personName
import app.novachat.ui.chat.previewText
import app.novachat.ui.chat.systemText
import app.novachat.R
import app.novachat.data.User
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import app.novachat.ui.common.Avatar
import app.novachat.ui.common.StatusTicks
import app.novachat.ui.common.formatListTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(openChat: (String) -> Unit, openNewGroup: () -> Unit, openJoin: () -> Unit, modifier: Modifier = Modifier) {
    val container = appContainer()
    val conversations by container.chats.conversations.collectAsStateWithLifecycle(initialValue = emptyList())
    val connection by container.chats.connection.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()
    val myId = (session as? SessionState.SignedIn)?.session?.user?.id
    val people by container.chats.people.collectAsStateWithLifecycle(initialValue = emptyMap())
    var newChat by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("NovaChat", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    if (connection != ConnectionState.Connected) {
                        Text("Connecting…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showMenu = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
            ) { Icon(Icons.Filled.Add, "New chat") }
        },
    ) { padding ->
        if (conversations.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No chats yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap + to message a friend by phone number, or to start a group.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(conversations, key = { it.peer.id }) { c ->
                    ConversationRow(c, myId, people) { openChat(c.peer.id) }
                }
            }
        }
    }

    if (showMenu) {
        ModalBottomSheet(onDismissRequest = { showMenu = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                MenuRow(rememberVectorPainter(Icons.Filled.Person), "New chat", "Message someone by phone number") {
                    showMenu = false
                    newChat = true
                }
                MenuRow(painterResource(R.drawable.ic_group), "New group", "Chat with several people at once") {
                    showMenu = false
                    openNewGroup()
                }
                MenuRow(painterResource(R.drawable.ic_link), "Join a group", "Paste an invite link") {
                    showMenu = false
                    openJoin()
                }
            }
        }
    }

    if (newChat) {
        NewChatDialog(onDismiss = { newChat = false }, onFound = { id ->
            newChat = false
            openChat(id)
        })
    }
}

@Composable
private fun MenuRow(icon: Painter, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConversationRow(c: Conversation, myId: String?, people: Map<String, User>, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.peer.displayName, c.peer.id, 52.dp, c.peer.avatarId)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.peer.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatListTime(c.lastMessage.createdAtMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (c.unread > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.lastMessage.senderId == myId && !c.lastMessage.system) {
                    StatusTicks(
                        c.lastMessage.status,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        readTint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    when {
                        c.lastMessage.system -> systemText(c.lastMessage, myId, people)
                        c.group != null && c.lastMessage.senderId != myId ->
                            personName(c.lastMessage.senderId, myId, people) + ": " + previewText(c.lastMessage)
                        else -> previewText(c.lastMessage)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (c.unread > 0) {
                    Badge(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary) {
                        Text(c.unread.toString(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NewChatDialog(onDismiss: () -> Unit, onFound: (String) -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("+") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New chat") },
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
