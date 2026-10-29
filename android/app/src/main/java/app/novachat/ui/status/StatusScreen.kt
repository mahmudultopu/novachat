package app.novachat.ui.status

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.R
import app.novachat.SessionState
import app.novachat.data.MediaException
import app.novachat.data.PreparedMedia
import app.novachat.data.Status
import app.novachat.data.User
import app.novachat.ui.appContainer
import app.novachat.ui.common.Avatar
import app.novachat.ui.common.formatListTime
import kotlinx.coroutines.launch

/**
 * The Status tab: your own updates on top, then updates from people you
 * chat with, unseen ones first. Everything disappears after 24 hours.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(openViewer: (String) -> Unit, openTextComposer: () -> Unit, modifier: Modifier = Modifier) {
    val container = appContainer()
    val repo = container.chats
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val statuses by repo.statuses.collectAsStateWithLifecycle(initialValue = emptyList())
    val people by repo.people.collectAsStateWithLifecycle(initialValue = emptyMap())
    val state by container.session.collectAsStateWithLifecycle()
    val me = (state as? SessionState.SignedIn)?.session?.user

    var prepared by remember { mutableStateOf<PreparedMedia?>(null) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val mime = context.contentResolver.getType(uri).orEmpty()
                    prepared = if (mime.startsWith("video/")) repo.media.prepareVideo(uri) else repo.media.preparePhoto(uri)
                } catch (e: MediaException) {
                    Toast.makeText(context, e.message ?: "Couldn't open that file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    fun pick() = pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))

    prepared?.let { p ->
        CaptionDialog(
            onDismiss = {
                p.file.delete()
                prepared = null
            },
            onPost = { caption ->
                repo.postMediaStatus(p, caption)
                prepared = null
            },
        )
    }

    val mine = statuses.filter { it.authorId == me?.id }
    val others = statuses.filter { it.authorId != me?.id }.groupBy { it.authorId }
        .entries.sortedByDescending { e -> e.value.maxOf { it.createdAtMillis } }
    val recent = others.filter { e -> e.value.any { !it.viewed } }
    val viewed = others.filter { e -> e.value.all { it.viewed } }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Status") }) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(onClick = openTextComposer, containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(Icons.Filled.Edit, "Write a text status")
                }
                FloatingActionButton(
                    onClick = ::pick,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(20.dp),
                ) { Icon(painterResource(R.drawable.ic_camera), "Post a photo or video") }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item(key = "mine") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { if (mine.isNotEmpty()) openViewer(me?.id.orEmpty()) else pick() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusRing(me, me?.id.orEmpty(), if (mine.isEmpty()) null else MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("My status", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                mine.isEmpty() -> "Tap to share a photo, video or text for 24 hours"
                                mine.any { !it.sent } -> "Sending…"
                                else -> "${mine.size} ${if (mine.size == 1) "update" else "updates"} · ${formatListTime(mine.last().createdAtMillis)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (others.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Updates from people you chat with show up here. Yours go to everyone you have a one-to-one chat with.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
            if (recent.isNotEmpty()) {
                item(key = "recent") { SectionLabel("Recent updates") }
                items(recent, key = { it.key }) { e ->
                    AuthorRow(e.key, people[e.key], e.value, MaterialTheme.colorScheme.secondary) { openViewer(e.key) }
                }
            }
            if (viewed.isNotEmpty()) {
                item(key = "viewed") { SectionLabel("Viewed updates") }
                items(viewed, key = { it.key }) { e ->
                    AuthorRow(e.key, people[e.key], e.value, MaterialTheme.colorScheme.outlineVariant) { openViewer(e.key) }
                }
            }
            item(key = "space") { Spacer(Modifier.height(140.dp)) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun AuthorRow(id: String, user: User?, items: List<Status>, ring: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusRing(user, id, ring)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(user?.displayName ?: "…", style = MaterialTheme.typography.titleMedium)
            Text(
                formatListTime(items.maxOf { it.createdAtMillis }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** An avatar inside a coloured ring: bright for new updates, grey for seen ones. */
@Composable
internal fun StatusRing(user: User?, id: String, ring: Color?, size: Dp = 52.dp) {
    Box(
        Modifier
            .border(2.5.dp, ring ?: Color.Transparent, CircleShape)
            .padding(4.dp),
    ) { Avatar(user?.displayName ?: "…", id, size, user?.avatarId) }
}

@Composable
private fun CaptionDialog(onDismiss: () -> Unit, onPost: (String) -> Unit) {
    var caption by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Post to your status") },
        text = {
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it.take(700) },
                label = { Text("Add a caption (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onPost(caption) }) { Text("Post") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
