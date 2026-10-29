package app.novachat.ui.call

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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.R
import app.novachat.SessionState
import app.novachat.ui.appContainer
import app.novachat.ui.common.Avatar
import app.novachat.ui.common.formatListTime
import app.novachat.ui.common.formatTime

/** Recent calls. Tap a row to open the chat, or the button to call again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(openChat: (String) -> Unit, modifier: Modifier = Modifier) {
    val container = appContainer()
    val logs by container.chats.callLogs.collectAsStateWithLifecycle(initialValue = emptyList())
    val people by container.chats.people.collectAsStateWithLifecycle(initialValue = emptyMap())
    val session by container.session.collectAsStateWithLifecycle()
    val myId = (session as? SessionState.SignedIn)?.session?.user?.id
    val call = rememberCallStarter()

    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("Calls") }) }) { padding ->
        if (logs.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(R.drawable.ic_call), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("No calls yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Open a chat and tap the phone or camera button at the top. Calls are end-to-end encrypted.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(logs, key = { it.senderId + it.clientId }) { m ->
                val log = m.callLog ?: return@items
                val outgoing = m.senderId == myId
                val person = people[m.peerId]
                val name = person?.displayName ?: "…"
                val missed = log.missed && !outgoing
                Row(
                    Modifier.fillMaxWidth().clickable { openChat(m.peerId) }.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name, m.peerId, 48.dp, person?.avatarId)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (missed) Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(callIcon(log, outgoing)),
                                null,
                                tint = if (missed) Color(0xFFE5484D) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                " " + formatListTime(m.createdAtMillis) + ", " + formatTime(m.createdAtMillis),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { call(m.peerId, log.video) }) {
                        Icon(
                            painterResource(if (log.video) R.drawable.ic_videocam else R.drawable.ic_call),
                            if (log.video) "Video call $name" else "Call $name",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
