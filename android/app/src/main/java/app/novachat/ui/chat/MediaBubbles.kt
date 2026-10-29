package app.novachat.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.R
import app.novachat.data.Attachment
import app.novachat.data.AttachmentKind
import app.novachat.data.ChatMessage
import app.novachat.ui.appContainer
import java.io.File
import java.util.Locale

/** The media part of a bubble: photo, video, voice note or document. */
@Composable
fun AttachmentView(m: ChatMessage, a: Attachment, fg: Color, player: VoicePlayer) {
    val repo = appContainer().chats
    val downloading by repo.downloading.collectAsStateWithLifecycle()
    val busy = m.clientId in downloading
    // Photos and voice notes are small, so they download as soon as they are shown.
    LaunchedEffect(m.clientId, m.localPath, a.id) {
        if (m.localPath == null && (a.kind == AttachmentKind.image || a.kind == AttachmentKind.voice)) repo.download(m)
    }
    when (a.kind) {
        AttachmentKind.image, AttachmentKind.video -> VisualMedia(m, a, busy, onDownload = { repo.download(m) })
        AttachmentKind.voice -> VoiceNote(m, a, fg, busy, player, onDownload = { repo.download(m) })
        AttachmentKind.file -> DocumentCard(m, a, fg, busy, onDownload = { repo.download(m) })
    }
}

@Composable
private fun VisualMedia(m: ChatMessage, a: Attachment, busy: Boolean, onDownload: () -> Unit) {
    val media = appContainer().chats.media
    val video = a.kind == AttachmentKind.video
    val preview by produceState<Bitmap?>(null, m.localPath) {
        value = m.localPath?.let { media.loadPreview(it, video, 900) }
    }
    val thumb = remember(a.thumb) { a.thumb?.let(media::decodeThumb) }
    var open by remember { mutableStateOf(false) }
    val ratio = ((a.width ?: 4).toFloat() / (a.height ?: 3).coerceAtLeast(1)).coerceIn(0.6f, 1.8f)

    Box(
        Modifier
            .width(250.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.15f))
            .clickable { if (m.localPath != null) open = true else onDownload() },
        contentAlignment = Alignment.Center,
    ) {
        (preview ?: thumb)?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        when {
            m.localPath == null && busy -> CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
            m.localPath == null -> RoundBadge {
                Icon(painterResource(R.drawable.ic_download), "Download", tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(formatSize(a.size), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            video -> Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
        if (video && a.durationMs != null) {
            Text(
                formatDuration(a.durationMs),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
    val path = m.localPath
    if (open && path != null) {
        if (video) VideoViewer(path) { open = false } else PhotoViewer(path) { open = false }
    }
}

@Composable
private fun RoundBadge(content: @Composable () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun VoiceNote(m: ChatMessage, a: Attachment, fg: Color, busy: Boolean, player: VoicePlayer, onDownload: () -> Unit) {
    val state by player.state.collectAsStateWithLifecycle()
    val path = m.localPath
    val active = path != null && state.path == path
    val total = (if (active && state.durationMs > 0) state.durationMs.toLong() else a.durationMs) ?: 0L
    Row(Modifier.width(230.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(fg.copy(alpha = 0.15f))
                .clickable(enabled = !busy) { if (path != null) player.toggle(path) else onDownload() },
            contentAlignment = Alignment.Center,
        ) {
            when {
                busy -> CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                path == null -> Icon(painterResource(R.drawable.ic_download), "Download", tint = fg)
                active && state.playing -> Icon(painterResource(R.drawable.ic_pause), "Pause", tint = fg)
                else -> Icon(Icons.Filled.PlayArrow, "Play", tint = fg)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val progress = if (active && total > 0) state.positionMs / total.toFloat() else 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                color = fg,
                trackColor = fg.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (active) formatDuration(state.positionMs.toLong()) + " / " + formatDuration(total) else formatDuration(total),
                color = fg.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun DocumentCard(m: ChatMessage, a: Attachment, fg: Color, busy: Boolean, onDownload: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.width(250.dp).clip(RoundedCornerShape(14.dp)).background(fg.copy(alpha = 0.1f))
            .clickable(enabled = !busy) { m.localPath?.let { openFile(context, it, a.mime) } ?: onDownload() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(fg.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                m.localPath == null -> Icon(painterResource(R.drawable.ic_download), "Download", tint = fg)
                else -> Icon(painterResource(R.drawable.ic_document), null, tint = fg)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name ?: "Document", color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val ext = a.name?.substringAfterLast('.', "")?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it.length <= 5 }
            Text(listOfNotNull(ext, formatSize(a.size)).joinToString(" · "), color = fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun openFile(context: Context, path: String, mime: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", File(path))
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this phone can open this file", Toast.LENGTH_SHORT).show()
    }
}

/** Full-screen photo with pinch to zoom. */
@Composable
private fun PhotoViewer(path: String, onClose: () -> Unit) {
    val media = appContainer().chats.media
    val bitmap by produceState<Bitmap?>(null, path) { value = media.loadPreview(path, false, 2048) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val zoom = rememberTransformableState { z, pan, _ ->
        scale = (scale * z).coerceIn(1f, 5f)
        offsetX += pan.x
        offsetY += pan.y
        if (scale == 1f) {
            offsetX = 0f
            offsetY = 0f
        }
    }
    FullScreen(onClose) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                null,
                Modifier.fillMaxSize().transformable(zoom).graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
                contentScale = ContentScale.Fit,
            )
        } ?: CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun VideoViewer(path: String, onClose: () -> Unit) {
    FullScreen(onClose) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                    setVideoPath(path)
                    setOnPreparedListener { start() }
                }
            },
            onRelease = { it.stopPlayback() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FullScreen(onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            content()
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, "Close", tint = Color.White)
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** One line describing a message for the chat list. */
fun previewText(m: ChatMessage): String {
    if (m.deleted) return "This message was deleted"
    if (m.undecryptable) return "Couldn't decrypt this message"
    val a = m.attachment ?: return m.body
    val label = when (a.kind) {
        AttachmentKind.image -> "Photo"
        AttachmentKind.video -> "Video"
        AttachmentKind.voice -> "Voice message" + (a.durationMs?.let { " (${formatDuration(it)})" } ?: "")
        AttachmentKind.file -> a.name ?: "Document"
    }
    return if (m.body.isNotBlank()) "$label · ${m.body}" else label
}
