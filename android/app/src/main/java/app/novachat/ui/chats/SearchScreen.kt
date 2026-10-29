package app.novachat.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.novachat.data.ChatMessage
import app.novachat.data.Conversation
import app.novachat.data.MessageRef
import app.novachat.data.User
import app.novachat.ui.appContainer
import app.novachat.ui.chat.personName
import app.novachat.ui.common.Avatar
import app.novachat.ui.common.formatListTime
import kotlinx.coroutines.delay

/** Searches chat names and every message on this phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    conversations: List<Conversation>,
    myId: String?,
    people: Map<String, User>,
    openChat: (String) -> Unit,
    openMessage: (String, MessageRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = appContainer().chats
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    var found by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    LaunchedEffect(query) {
        delay(250) // wait for typing to pause
        found = repo.searchMessages(query)
    }
    val q = query.trim()
    val chats = if (q.isEmpty()) emptyList() else conversations.filter { it.peer.displayName.contains(q, ignoreCase = true) }
    val byId = conversations.associateBy { it.peer.id }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
                title = {
                    TextField(
                        value = query,
                        onValueChange = onQuery,
                        placeholder = { Text("Search chats and messages") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (q.isNotEmpty() && chats.isEmpty() && found.isEmpty()) {
                item {
                    Text(
                        "Nothing found for \"$q\"",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            if (chats.isNotEmpty()) {
                item { Header("Chats") }
                items(chats, key = { "c" + it.peer.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { openChat(c.peer.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(c.peer.displayName, c.peer.id, 44.dp, c.peer.avatarId)
                        Spacer(Modifier.width(14.dp))
                        Text(c.peer.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (found.isNotEmpty()) {
                item { Header("Messages") }
                items(found, key = { "m" + it.senderId + it.clientId }) { m ->
                    val chat = byId[m.peerId]?.peer
                    val chatName = chat?.displayName ?: personName(m.peerId, myId, people)
                    Column(
                        Modifier.fillMaxWidth().clickable { openMessage(m.peerId, m.ref) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                chatName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatListTime(m.createdAtMillis),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val sender = if (m.senderId == myId) "You: " else if (m.groupId != null) personName(m.senderId, myId, people) + ": " else ""
                        Text(
                            highlight(sender, snippet(m.body, q), q, MaterialTheme.colorScheme.primary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** The part of [body] around the first match, so long messages still show it. */
internal fun snippet(body: String, query: String): String {
    val at = body.indexOf(query, ignoreCase = true)
    if (at <= 40) return body
    return "…" + body.substring(at - 30)
}

private fun highlight(prefix: String, text: String, query: String, color: Color) = buildAnnotatedString {
    append(prefix)
    var from = 0
    while (query.isNotEmpty()) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        append(text.substring(from, at))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(text.substring(at, at + query.length)) }
        from = at + query.length
    }
    append(text.substring(from))
}
