package app.novachat.ui.chats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import app.novachat.data.MessageRef
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    openChat: (String) -> Unit,
    openMessage: (String, MessageRef) -> Unit,
    openNewGroup: () -> Unit,
    openJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = appContainer()
    val conversations by container.chats.conversations.collectAsStateWithLifecycle(initialValue = emptyList())
    val connection by container.chats.connection.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()
    val myId = (session as? SessionState.SignedIn)?.session?.user?.id
    val people by container.chats.people.collectAsStateWithLifecycle(initialValue = emptyMap())
    var newChat by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Conversation?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val archived = conversations.filter { it.prefs.archived }
    val shown = if (showArchived) archived else conversations.filterNot { it.prefs.archived }
    LaunchedEffect(archived.isEmpty()) { if (archived.isEmpty()) showArchived = false }
    BackHandler(enabled = showArchived) { showArchived = false }

    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    BackHandler(enabled = searching) { searching = false; query = "" }
    if (searching) {
        SearchScreen(
            query = query,
            onQuery = { query = it },
            onClose = { searching = false; query = "" },
            conversations = conversations,
            myId = myId,
            people = people,
            openChat = openChat,
            openMessage = openMessage,
            modifier = modifier,
        )
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (showArchived) {
                TopAppBar(
                    title = { Text("Archived") },
                    navigationIcon = { IconButton(onClick = { showArchived = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                )
            } else TopAppBar(
                title = {
                    Column {
                        Text("NovaChat", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        if (connection != ConnectionState.Connected) {
                            Text("Connecting…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (conversations.isNotEmpty()) {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showMenu = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
            ) { Icon(Icons.Filled.Add, "New chat") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
                if (showArchived) {
                    item {
                        Text(
                            "Archived chats stay here when new messages arrive. Long-press a chat to unarchive it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
                        )
                    }
                } else if (archived.isNotEmpty()) {
                    item(key = "archived") {
                        ArchivedRow(unreadChats = archived.count { it.unread > 0 }) { showArchived = true }
                    }
                }
                items(shown, key = { it.peer.id }) { c ->
                    ConversationRow(c, myId, people, onLongClick = { actionsFor = c }) { openChat(c.peer.id) }
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

    actionsFor?.let { c ->
        ChatActionsSheet(c, scope, onDismiss = { actionsFor = null }, onMessage = { msg ->
            scope.launch { snackbar.showSnackbar(msg) }
        })
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
private fun ArchivedRow(unreadChats: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_archive), null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(14.dp))
        Text("Archived", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (unreadChats > 0) {
            Text(unreadChats.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    c: Conversation,
    myId: String?,
    people: Map<String, User>,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
                val faint = MaterialTheme.colorScheme.onSurfaceVariant
                if (c.prefs.muted) {
                    Icon(painterResource(R.drawable.ic_muted), "Muted", Modifier.padding(start = 4.dp).size(16.dp), tint = faint)
                }
                if (c.prefs.pinned) {
                    Icon(painterResource(R.drawable.ic_pin), "Pinned", Modifier.padding(start = 4.dp).size(16.dp), tint = faint)
                }
                if (c.unread > 0) {
                    Spacer(Modifier.width(4.dp))
                    Badge(
                        containerColor = if (c.prefs.muted) faint else MaterialTheme.colorScheme.secondary,
                        contentColor = if (c.prefs.muted) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSecondary,
                    ) {
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

/** Long-press menu for a chat: pin, mute and archive. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatActionsSheet(c: Conversation, scope: CoroutineScope, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    // [scope] belongs to the chat list, so the change still saves after this sheet closes.
    val repo = appContainer().chats
    var choosingMute by remember { mutableStateOf(false) }
    val id = c.peer.id

    fun run(block: suspend () -> Unit) {
        onDismiss()
        scope.launch { block() }
    }

    if (choosingMute) {
        MuteDialog(
            onDismiss = { choosingMute = false; onDismiss() },
            onPick = { until -> run { repo.setMuted(id, until) } },
        )
        return
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                c.peer.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (!c.prefs.archived) {
                ActionRow(R.drawable.ic_pin, if (c.prefs.pinned) "Unpin chat" else "Pin chat") {
                    run {
                        if (!repo.setPinned(id, !c.prefs.pinned)) onMessage("You can pin up to 3 chats")
                    }
                }
            }
            if (c.prefs.muted) {
                ActionRow(R.drawable.ic_muted, "Unmute notifications") { run { repo.setMuted(id, null) } }
            } else {
                ActionRow(R.drawable.ic_muted, "Mute notifications") { choosingMute = true }
            }
            if (c.prefs.archived) {
                ActionRow(R.drawable.ic_unarchive, "Unarchive chat") { run { repo.setArchived(id, false) } }
            } else {
                ActionRow(R.drawable.ic_archive, "Archive chat") {
                    run {
                        repo.setArchived(id, true)
                        onMessage("Chat archived")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** How long to silence a chat for. */
@Composable
internal fun MuteDialog(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val hour = 60 * 60 * 1000L
    val options = listOf("8 hours" to 8 * hour, "1 week" to 7 * 24 * hour, "Always" to Long.MAX_VALUE)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mute notifications for…") },
        text = {
            Column {
                Text(
                    "You won't be notified about new messages. You'll still see them in the chat.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                options.forEach { (label, length) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val now = System.currentTimeMillis()
                                onPick(if (length == Long.MAX_VALUE) length else now + length)
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
