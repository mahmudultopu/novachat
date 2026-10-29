package app.novachat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.SessionState
import app.novachat.ui.appContainer
import app.novachat.ui.chat.previewText
import app.novachat.ui.common.Avatar
import app.novachat.ui.common.formatListTime

/** Every starred message, newest first. Tap one to open its chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(onBack: () -> Unit, openChat: (String) -> Unit) {
    val container = appContainer()
    val repo = container.chats
    val starred by repo.starred.collectAsStateWithLifecycle(initialValue = emptyList())
    val conversations by repo.conversations.collectAsStateWithLifecycle(initialValue = emptyList())
    val session by container.session.collectAsStateWithLifecycle()
    val me = (session as? SessionState.SignedIn)?.session?.user
    val chats = conversations.associate { it.peer.id to it.peer }
    val people by repo.people.collectAsStateWithLifecycle(initialValue = emptyMap())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Starred messages") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (starred.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Star, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "Long-press any message and tap Star to find it here later.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(starred, key = { it.senderId + it.clientId }) { m ->
                val fromMe = m.senderId == me?.id
                val chat = chats[m.peerId]
                val sender = if (fromMe) me else people[m.senderId] ?: chat
                Row(
                    Modifier.fillMaxWidth().clickable { openChat(m.peerId) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Avatar(sender?.displayName ?: "…", m.senderId, 40.dp, sender?.avatarId)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val title = when {
                                fromMe -> "You → ${chat?.displayName ?: "…"}"
                                m.groupId != null -> "${sender?.displayName ?: "…"} · ${chat?.displayName ?: "group"}"
                                else -> chat?.displayName ?: "…"
                            }
                            Text(
                                title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(formatListTime(m.createdAtMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(previewText(m), maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(Modifier.padding(start = 68.dp))
            }
        }
    }
}
