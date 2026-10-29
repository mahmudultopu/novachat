package app.novachat.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.novachat.R
import app.novachat.data.AttachmentKind
import app.novachat.data.MediaException
import app.novachat.data.PreparedMedia
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
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
    var showSafety by remember { mutableStateOf(false) }
    if (showSafety) {
        SafetyNumberDialog(peerName = peer?.displayName ?: "them", load = { repo.safetyNumber(peerId) }, onDismiss = { showSafety = false })
    }
    var lastTypingSent by remember { mutableLongStateOf(0L) }

    // Media: pickers, camera, voice notes.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { VoicePlayer(scope) }
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var recordMs by remember { mutableLongStateOf(0L) }
    var showAttach by remember { mutableStateOf(false) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            recorder.cancel()
        }
    }
    LaunchedEffect(recording) {
        while (recording) {
            recordMs = recorder.elapsedMs()
            delay(200)
        }
    }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun sendPrepared(prepare: suspend () -> PreparedMedia) {
        scope.launch {
            try {
                repo.sendAttachment(peerId, prepare())
            } catch (e: MediaException) {
                toast(e.message ?: "Couldn't send that file")
            }
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            sendPrepared {
                val mime = context.contentResolver.getType(uri).orEmpty()
                if (mime.startsWith("video/")) repo.media.prepareVideo(uri) else repo.media.preparePhoto(uri)
            }
        }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) sendPrepared { repo.media.preparePhoto(Uri.parse(u)) }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendPrepared { repo.media.prepareDocument(uri) }
    }
    fun startRecording() {
        player.release()
        if (recorder.start(repo.media.newFile("m4a"))) recording = true else toast("Couldn't start the microphone")
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else toast("NovaChat needs the microphone to record voice messages")
    }

    if (showAttach) {
        ModalBottomSheet(onDismissRequest = { showAttach = false }) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                AttachOption(R.drawable.ic_gallery, "Photos & videos", MaterialTheme.colorScheme.primary) {
                    showAttach = false
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                AttachOption(R.drawable.ic_camera, "Camera", MaterialTheme.colorScheme.secondary) {
                    showAttach = false
                    val file = File(context.cacheDir, "camera").apply { mkdirs() }.let { File(it, "${System.currentTimeMillis()}.jpg") }
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    cameraUri = uri.toString()
                    takePicture.launch(uri)
                }
                AttachOption(R.drawable.ic_document, "Document", Color(0xFFF29B38)) {
                    showAttach = false
                    openDocument.launch(arrayOf("*/*"))
                }
            }
        }
    }

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
                    Bubble(m, mine = m.senderId == myId, player = player, onRetry = { repo.retry(m.clientId) })
                }
                // reverseLayout: the last item sits above the oldest message.
                item(key = "e2e-notice") { EncryptionNotice(onClick = { showSafety = true }) }
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
                recordingMs = if (recording) recordMs else null,
                onAttach = { showAttach = true },
                onMic = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (granted) startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onCancelRecording = {
                    recorder.cancel()
                    recording = false
                },
                onSendRecording = {
                    recording = false
                    val result = recorder.stop()
                    if (result == null) {
                        toast("Hold on a moment longer to record a voice message")
                    } else {
                        repo.sendAttachment(peerId, repo.media.prepareVoice(result.first, result.second))
                    }
                },
            )
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, mine: Boolean, player: VoicePlayer, onRetry: () -> Unit) {
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
            val attachment = m.attachment.takeUnless { m.undecryptable }
            val visual = attachment != null && (attachment.kind == AttachmentKind.image || attachment.kind == AttachmentKind.video)
            val pad = if (visual) {
                Modifier.padding(start = 5.dp, end = 5.dp, top = 5.dp, bottom = 6.dp)
            } else {
                Modifier.padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
            }
            Column(pad) {
                if (m.undecryptable) {
                    Text(
                        "This message couldn't be decrypted on this phone",
                        color = fg.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = FontStyle.Italic,
                    )
                } else if (attachment != null) {
                    AttachmentView(m, attachment, fg, player)
                    if (m.body.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(m.body, color = fg, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 6.dp))
                    }
                } else {
                    Text(m.body, color = fg, style = MaterialTheme.typography.bodyLarge)
                }
                Row(
                    Modifier.align(Alignment.End).padding(end = if (visual) 4.dp else 0.dp, top = 2.dp),
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
private fun MessageComposer(
    text: String,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    recordingMs: Long?,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    onCancelRecording: () -> Unit,
    onSendRecording: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
        ) {
            if (recordingMs != null) {
                Row(Modifier.padding(horizontal = 6.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancelRecording) {
                        Icon(Icons.Filled.Delete, "Discard recording", tint = MaterialTheme.colorScheme.error)
                    }
                    Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    Spacer(Modifier.width(10.dp))
                    Text("Recording  " + formatDuration(recordingMs), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = onAttach, modifier = Modifier.padding(bottom = 2.dp)) {
                        Icon(painterResource(R.drawable.ic_attach), "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextField(
                        value = text,
                        onValueChange = onText,
                        placeholder = { Text("Message") },
                        maxLines = 6,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        val sendNow = recordingMs != null || text.isNotBlank()
        FilledIconButton(
            onClick = { if (recordingMs != null) onSendRecording() else if (text.isNotBlank()) onSend() else onMic() },
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.size(52.dp),
        ) {
            if (sendNow) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            } else {
                Icon(painterResource(R.drawable.ic_mic), "Record voice message")
            }
        }
    }
}

@Composable
private fun AttachOption(icon: Int, label: String, color: Color, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(60.dp).clip(RoundedCornerShape(20.dp)).background(color), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EncryptionNotice(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Messages here are end-to-end encrypted. Only the two of you can read them, not even NovaChat. Tap to verify.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Shows the safety number in 12 groups of 5 digits, like a short code to read aloud. */
@Composable
private fun SafetyNumberDialog(peerName: String, load: suspend () -> String?, onDismiss: () -> Unit) {
    var number by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        number = load()
        loaded = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Verify encryption") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val n = number
                when {
                    !loaded -> CircularProgressIndicator(Modifier.size(28.dp))
                    n == null -> Text("Send or receive a message first. Encryption keys are set up with the first message.")
                    else -> {
                        Text(
                            n.chunked(5).chunked(4).joinToString("\n") { it.joinToString("   ") },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Compare these numbers with $peerName, in person or on a call. If they match, your chat is private.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
