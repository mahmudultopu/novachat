package app.novachat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.novachat.R
import app.novachat.data.ChatMessage
import app.novachat.data.Conversation
import app.novachat.ui.common.Avatar

/** The quick reactions offered on every message. */
val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/** What you can do with a message: react on top, actions below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActionsSheet(
    m: ChatMessage,
    myReaction: String?,
    canReact: Boolean,
    canEdit: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onStar: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            if (canReact) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for (emoji in QuickReactions) {
                        val chosen = emoji == myReaction
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { onReact(if (chosen) "" else emoji) },
                            contentAlignment = Alignment.Center,
                        ) { Text(emoji, fontSize = 26.sp) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            val content = !m.deleted && !m.undecryptable
            if (!m.deleted) ActionRow(painterResource(R.drawable.ic_reply), "Reply", onReply)
            if (content && m.body.isNotEmpty()) ActionRow(painterResource(R.drawable.ic_copy), "Copy text", onCopy)
            if (content) ActionRow(painterResource(R.drawable.ic_forward), "Forward", onForward)
            if (!m.deleted) {
                ActionRow(
                    if (m.starred) painterResource(R.drawable.ic_star_outline) else rememberVectorPainter(Icons.Filled.Star),
                    if (m.starred) "Unstar" else "Star",
                    onStar,
                )
            }
            if (canEdit) ActionRow(rememberVectorPainter(Icons.Filled.Edit), "Edit", onEdit)
            ActionRow(rememberVectorPainter(Icons.Filled.Delete), "Delete", onDelete, MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ActionRow(icon: Painter, label: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@Composable
internal fun DeleteMessageDialog(canDeleteForEveryone: Boolean, onEveryone: () -> Unit, onMe: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete message?") },
        text = {
            Column {
                if (canDeleteForEveryone) {
                    TextButton(onClick = onEveryone, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete for everyone", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onMe, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete for me", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick one or more chats to forward a message to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForwardSheet(conversations: List<Conversation>, onSend: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf(emptySet<String>()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text("Forward to…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (conversations.isEmpty()) {
                Text(
                    "Start a chat first.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(conversations, key = { it.peer.id }) { c ->
                    val on = c.peer.id in picked
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { picked = if (on) picked - c.peer.id else picked + c.peer.id }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(c.peer.displayName, c.peer.id, 42.dp, c.peer.avatarId)
                        Spacer(Modifier.width(14.dp))
                        Text(c.peer.displayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Checkbox(checked = on, onCheckedChange = { picked = if (on) picked - c.peer.id else picked + c.peer.id })
                    }
                }
            }
            Button(
                onClick = { onSend(picked) },
                enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).height(50.dp),
            ) { Text(if (picked.size > 1) "Send to ${picked.size} chats" else "Send") }
        }
    }
}

/** The quoted message at the top of a reply bubble, or above the composer. */
@Composable
internal fun ReplyQuote(name: String, text: String, accent: Color, fg: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(fg.copy(alpha = 0.08f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(name, color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(text, color = fg.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Shown above the composer while replying to or editing a message. */
@Composable
internal fun ComposerBanner(name: String, text: String, onClose: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ReplyQuote(
                name,
                text,
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.onSurface,
                Modifier.weight(1f),
            )
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel") }
        }
    }
}

/** The emoji pill under a bubble. Tapping it takes your own reaction back. */
@Composable
internal fun ReactionsPill(reactions: Map<String, String>, mine: Boolean, onClick: () -> Unit) {
    val counts = reactions.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.padding(start = if (mine) 0.dp else 10.dp, end = if (mine) 10.dp else 0.dp).widthIn(min = 28.dp),
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            counts.take(3).forEach { Text(it.key, fontSize = 14.sp) }
            if (reactions.size > 1) {
                Spacer(Modifier.width(3.dp))
                Text("${reactions.size}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** "This message was deleted", in the bubble's own colours. */
@Composable
internal fun DeletedText(mine: Boolean, fg: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_block), null, Modifier.size(16.dp), tint = fg.copy(alpha = 0.6f))
        Spacer(Modifier.width(6.dp))
        Text(
            if (mine) "You deleted this message" else "This message was deleted",
            color = fg.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
internal fun ForwardedLabel(fg: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
        Icon(painterResource(R.drawable.ic_forward), null, Modifier.size(14.dp), tint = fg.copy(alpha = 0.6f))
        Spacer(Modifier.width(4.dp))
        Text("Forwarded", color = fg.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
    }
}
