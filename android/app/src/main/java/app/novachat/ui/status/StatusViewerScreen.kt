package app.novachat.ui.status

import android.graphics.Bitmap
import android.widget.VideoView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.SessionState
import app.novachat.data.AttachmentKind
import app.novachat.data.Status
import app.novachat.ui.appContainer
import app.novachat.ui.common.Avatar
import app.novachat.ui.common.formatListTime
import app.novachat.ui.common.formatTime
import kotlinx.coroutines.launch

private const val PHOTO_MS = 5_000
private const val MAX_VIDEO_MS = 30_000

/**
 * Plays one person's status updates in order. Tap the right side for the
 * next one, the left side for the previous one, and hold to pause.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusViewerScreen(authorId: String, onClose: () -> Unit) {
    val container = appContainer()
    val repo = container.chats
    val all by repo.statuses.collectAsStateWithLifecycle(initialValue = null)
    val people by repo.people.collectAsStateWithLifecycle(initialValue = emptyMap())
    val state by container.session.collectAsStateWithLifecycle()
    val me = (state as? SessionState.SignedIn)?.session?.user
    val list = all?.filter { it.authorId == authorId }.orEmpty()
    val mine = authorId == me?.id

    // Start at the first update you haven't seen yet.
    var index by remember { mutableIntStateOf(-1) }
    LaunchedEffect(all == null) {
        if (all != null && index < 0) index = list.indexOfFirst { !it.viewed }.coerceAtLeast(0)
    }
    if (all != null && list.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val current = list.getOrNull(index.coerceAtMost(list.lastIndex)) ?: return
    val author = if (mine) me else people[authorId]

    val progress = remember(current.id) { Animatable(0f) }
    var paused by remember { mutableStateOf(false) }
    var showViewers by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val needsFile = current.attachment != null
    val ready = !needsFile || current.localPath != null
    val durationMs = when (current.attachment?.kind) {
        AttachmentKind.video -> (current.attachment?.durationMs ?: PHOTO_MS.toLong()).toInt().coerceIn(1_000, MAX_VIDEO_MS)
        else -> PHOTO_MS
    }

    fun next() {
        if (index < list.lastIndex) index++ else onClose()
    }
    fun previous() {
        if (index > 0) index--
    }

    LaunchedEffect(current.id) {
        repo.viewStatus(current)
        repo.downloadStatus(current)
    }
    // Run the progress bar while the update is ready and not held down.
    LaunchedEffect(current.id, ready, paused, showViewers) {
        if (!ready || paused || showViewers) {
            progress.stop()
            return@LaunchedEffect
        }
        val remaining = ((1f - progress.value) * durationMs).toInt()
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        next()
    }

    if (showViewers) {
        ModalBottomSheet(onDismissRequest = { showViewers = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    "Viewed by ${current.viewers.size}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                if (current.viewers.isEmpty()) {
                    Text(
                        "No views yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(current.viewers.entries.sortedByDescending { it.value }, key = { it.key }) { (id, at) ->
                        val u = people[id]
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(u?.displayName ?: "…", id, 40.dp, u?.avatarId)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(u?.displayName ?: "…", style = MaterialTheme.typography.titleSmall)
                                Text(formatListTime(at) + " " + formatTime(at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(current.background?.let { Color(it) } ?: Color.Black)
            .pointerInput(current.id) {
                detectTapGestures(
                    onPress = {
                        paused = true
                        tryAwaitRelease()
                        paused = false
                    },
                    onTap = { offset -> if (offset.x < size.width / 3f) previous() else next() },
                )
            },
    ) {
        StatusContent(current, ready)

        Column(Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
            // One bar per update; the current one fills up.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                list.forEachIndexed { i, _ ->
                    val fill = when {
                        i < index -> 1f
                        i == index -> progress.value
                        else -> 0f
                    }
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f))) {
                        Box(Modifier.fillMaxWidth(fill).height(3.dp).background(Color.White))
                    }
                }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                Avatar(author?.displayName ?: "…", authorId, 36.dp, author?.avatarId)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (mine) "My status" else author?.displayName ?: "…", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        formatListTime(current.createdAtMillis) + " " + formatTime(current.createdAtMillis),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (mine) {
                    IconButton(onClick = {
                        scope.launch { progress.stop() }
                        repo.deleteStatus(current)
                    }) { Icon(Icons.Filled.Delete, "Delete this update", tint = Color.White) }
                }
            }
        }

        if (mine) {
            Surface(
                onClick = { showViewers = true },
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 16.dp),
            ) {
                Text(
                    if (!current.sent) "Sending…" else "👁  ${current.viewers.size}",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusContent(s: Status, ready: Boolean) {
    val media = appContainer().chats.media
    val a = s.attachment
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            a == null -> Text(
                s.text,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
            !ready -> {
                val thumb = remember(a.thumb) { a.thumb?.let(media::decodeThumb) }
                if (thumb != null) {
                    Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize().blur(24.dp), contentScale = ContentScale.Crop)
                }
                CircularProgressIndicator(color = Color.White)
            }
            a.kind == AttachmentKind.video -> AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(s.localPath)
                        setOnPreparedListener { start() }
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.fillMaxWidth(),
            )
            else -> {
                val bitmap by produceState<Bitmap?>(null, s.localPath) {
                    value = s.localPath?.let { media.loadPreview(it, false, 1600) }
                }
                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
        }
        if (a != null && s.text.isNotBlank()) {
            Text(
                s.text,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .systemBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 64.dp),
            )
        }
    }
}
