package app.novachat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.SessionState
import app.novachat.data.ChatMessage
import app.novachat.data.DeliveryStatus
import app.novachat.ui.appContainer
import app.novachat.ui.common.Avatar
import app.novachat.ui.common.StatusTicks
import app.novachat.ui.common.formatTime
import app.novachat.ui.theme.LocalBubbleColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(peerId: String, onBack: () -> Unit) {
    val container = appContainer()
    val repo = container.chats
    val messages by remember(peerId) { repo.messagesWith(peerId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val peer by remember(peerId) { repo.user(peerId) }.collectAsStateWithLifecycle(initialValue = null)
    val typing by remember(peerId) { repo.isTyping(peerId) }.collectAsStateWithLifecycle(initialValue = false)
    val session by container.session.collectAsStateWithLifecycle()
    val myId = (session as? SessionState.SignedIn)?.session?.user?.id

    // Incoming messages are marked read only while this chat is on screen.
    DisposableEffect(peerId) {
        repo.setOpenChat(peerId)
        onDispose { repo.setOpenChat(null) }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    var draft by rememberSaveable { mutableStateOf("") }
    var lastTypingSent by remember { mutableLongStateOf(0L) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val name = peer?.displayName ?: "…"
                        Avatar(name, peerId, 38.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val subtitle = if (typing) "typing…" else peer?.phone.orEmpty()
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (typing) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .imePadding()
                .navigationBarsPadding(),
        ) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messages.asReversed(), key = { it.senderId + it.clientId }) { m ->
                    Bubble(m, mine = m.senderId == myId, onRetry = { repo.retry(peerId, m.clientId) })
                }
            }
            MessageComposer(
                text = draft,
                onText = {
                    draft = it
                    val now = System.currentTimeMillis()
                    if (it.isNotBlank() && now - lastTypingSent > 3_000) {
                        lastTypingSent = now
                        repo.sendTyping(peerId)
                    }
                },
                onSend = {
                    repo.send(peerId, draft)
                    draft = ""
                    lastTypingSent = 0
                },
            )
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, mine: Boolean, onRetry: () -> Unit) {
    val colors = LocalBubbleColors.current
    // Rounded "pill" bubbles with one tighter corner pointing at the sender.
    val shape = if (mine) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
    }
    val bg = if (mine) colors.mine else colors.theirs
    val fg = if (mine) colors.onMine else colors.onTheirs
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = bg,
            shape = shape,
            shadowElevation = if (mine) 0.dp else 1.dp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .then(if (m.status == DeliveryStatus.Failed) Modifier.clickable(onClick = onRetry) else Modifier),
        ) {
            Column(Modifier.padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
                Text(m.body, color = fg, style = MaterialTheme.typography.bodyLarge)
                Row(
                    Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (m.status == DeliveryStatus.Failed) {
                        Text("Not sent · tap to retry  ", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f))
                    }
                    Text(formatTime(m.createdAtMillis), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                    if (mine) {
                        Spacer(Modifier.width(4.dp))
                        StatusTicks(m.status, tint = fg.copy(alpha = 0.7f), readTint = colors.readTick, size = 15.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageComposer(text: String, onText: (String) -> Unit, onSend: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            value = text,
            onValueChange = onText,
            placeholder = { Text("Message") },
            maxLines = 6,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = onSend,
            enabled = text.isNotBlank(),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.size(52.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, "Send")
        }
    }
}
