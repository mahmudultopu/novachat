package app.novachat.ui.chat

import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Search
import app.novachat.data.ApiException
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import app.novachat.data.MessageRef
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
import app.novachat.ui.call.CallLogRow
import app.novachat.ui.call.rememberCallStarter
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
import app.novachat.ui.common.formatLastSeen
import app.novachat.ui.common.formatTime
import app.novachat.ui.theme.LocalBubbleColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(peerId: String, onBack: () -> Unit, openInfo: (isGroup: Boolean) -> Unit, focus: MessageRef? = null) {
    val container = appContainer()
    val repo = container.chats
    val messages by remember(peerId) { repo.messagesWith(peerId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val peer by remember(peerId) { repo.user(peerId) }.collectAsStateWithLifecycle(initialValue = null)
    // Group chats use the group's ID where a 1:1 chat uses the other person's.
    val group by remember(peerId) { repo.group(peerId) }.collectAsStateWithLifecycle(initialValue = null)
    val people by repo.people.collectAsStateWithLifecycle(initialValue = emptyMap())
    val typing by remember(peerId) { repo.isTyping(peerId) }.collectAsStateWithLifecycle(initialValue = false)
    val presence by remember(peerId) { repo.presence(peerId) }.collectAsStateWithLifecycle(initialValue = null)
    val waitingForKeys by remember(peerId) { repo.waitingForKeys(peerId) }.collectAsStateWithLifecycle(initialValue = false)
    val blocked by remember(peerId) { repo.isBlocked(peerId) }.collectAsStateWithLifecycle(initialValue = false)
    val session by container.session.collectAsStateWithLifecycle()
    val myId = (session as? SessionState.SignedIn)?.session?.user?.id

    // Incoming messages are marked read only while this chat is on screen.
    DisposableEffect(peerId) {
        repo.setOpenChat(peerId)
        container.notifier.clear(peerId)
        onDispose { repo.setOpenChat(null) }
    }

    val listState = rememberLazyListState()

    var draft by rememberSaveable { mutableStateOf("") }
    var showSafety by remember { mutableStateOf(false) }

    // Replies, reactions, edits, deletes and forwards.
    val conversations by repo.conversations.collectAsStateWithLifecycle(initialValue = emptyList())
    val byRef = remember(messages) { messages.associateBy { it.ref } }
    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var deleteFor by remember { mutableStateOf<ChatMessage?>(null) }
    var forwardFor by remember { mutableStateOf<ChatMessage?>(null) }
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    var highlighted by remember { mutableStateOf<MessageRef?>(null) }

    // Opened from a search result: show that message first, then follow new ones.
    var focusDone by remember { mutableStateOf(focus == null) }
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (!focusDone) {
            focusDone = true
            val index = messages.asReversed().indexOfFirst { it.ref == focus }
            if (index >= 0) {
                listState.scrollToItem(index)
                highlighted = focus
                delay(2_000)
                highlighted = null
                return@LaunchedEffect
            }
        }
        listState.animateScrollToItem(0)
    }

    // Searching this chat: the newest match first, arrows step through the rest.
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchIndex by remember { mutableIntStateOf(0) }
    val matches = remember(messages, query) { searchMatches(messages, query) }
    LaunchedEffect(query, matchIndex, searching) {
        val ref = matches.getOrNull(matchIndex)
        if (!searching || ref == null) return@LaunchedEffect
        val index = messages.asReversed().indexOfFirst { it.ref == ref }
        if (index >= 0) listState.animateScrollToItem(index)
        highlighted = ref
    }
    fun closeSearch() {
        searching = false
        query = ""
        highlighted = null
    }
    BackHandler(enabled = searching) { closeSearch() }
    val clipboard = LocalClipboardManager.current
    fun nameOf(senderId: String) = when {
        senderId == myId -> "You"
        group != null -> personName(senderId, myId, people)
        else -> peer?.displayName ?: "Them"
    }
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
                repo.sendAttachment(peerId, prepare(), replyTo = replyTo?.ref)
                replyTo = null
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
    fun openCamera() {
        val file = File(context.cacheDir, "camera").apply { mkdirs() }.let { File(it, "${System.currentTimeMillis()}.jpg") }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        cameraUri = uri.toString()
        takePicture.launch(uri)
    }
    // NovaChat holds the camera permission for video calls, so Android asks for it here too.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else toast("NovaChat needs the camera to take photos")
    }
    val startCall = rememberCallStarter()
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

    actionsFor?.let { m ->
        val close = { actionsFor = null }
        MessageActionsSheet(
            m = m,
            myReaction = myId?.let { m.reactions[it] },
            canReact = !m.deleted && !m.undecryptable && m.id != null,
            canEdit = repo.canEdit(m, myId),
            onDismiss = close,
            onReact = { repo.react(m, it); close() },
            onReply = { replyTo = m; editing = null; close() },
            onCopy = {
                clipboard.setText(AnnotatedString(m.body))
                toast("Copied")
                close()
            },
            onForward = { forwardFor = m; close() },
            onStar = { repo.setStarred(m, !m.starred); close() },
            onEdit = { editing = m; replyTo = null; draft = m.body; close() },
            onDelete = { deleteFor = m; close() },
        )
    }
    deleteFor?.let { m ->
        DeleteMessageDialog(
            canDeleteForEveryone = repo.canDeleteForEveryone(m, myId),
            onEveryone = { repo.deleteForEveryone(m); deleteFor = null },
            onMe = { repo.deleteForMe(m); deleteFor = null },
            onDismiss = { deleteFor = null },
        )
    }
    forwardFor?.let { m ->
        ForwardSheet(
            conversations = conversations,
            onSend = { peers ->
                repo.forward(m, peers)
                forwardFor = null
                toast(if (peers.size > 1) "Forwarded to ${peers.size} chats" else "Forwarded")
            },
            onDismiss = { forwardFor = null },
        )
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
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (granted) openCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
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
            if (searching) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { closeSearch() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
                    title = { ChatSearchField(query, onQuery = { query = it; matchIndex = 0 }, onSearch = {}) },
                    actions = {
                        ChatSearchArrows(
                            index = matchIndex,
                            count = matches.size,
                            onOlder = { matchIndex++ },
                            onNewer = { matchIndex-- },
                        )
                    },
                )
            } else TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    Row(
                        Modifier.clickable { openInfo(group != null) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val g = group
                        val name = g?.name ?: peer?.displayName ?: "…"
                        Avatar(name, peerId, 38.dp, g?.avatarId ?: peer?.avatarId)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val subtitle = when {
                                g != null -> g.members
                                    .sortedBy { it.userId != myId }
                                    .joinToString(", ") { nameOf(it.userId) }
                                typing -> "typing…"
                                else -> formatLastSeen(presence?.online == true, presence?.lastSeenAt ?: peer?.lastSeenAt)
                                    ?: peer?.phone.orEmpty()
                            }
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    subtitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (typing) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
                    if (group == null && !blocked) {
                        IconButton(onClick = { startCall(peerId, true) }) {
                            Icon(painterResource(R.drawable.ic_videocam), "Video call")
                        }
                        IconButton(onClick = { startCall(peerId, false) }) {
                            Icon(painterResource(R.drawable.ic_call), "Voice call")
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
                val shown = messages.asReversed()
                items(shown, key = { it.senderId + it.clientId }) { m ->
                    if (m.system) {
                        SystemNotice(systemText(m, myId, people))
                        return@items
                    }
                    m.callLog?.let { log ->
                        CallLogRow(m, log, mine = m.senderId == myId, onCallBack = { startCall(peerId, log.video) })
                        return@items
                    }
                    val quoted = m.replyTo?.let { byRef[it] }
                    Bubble(
                        m,
                        mine = m.senderId == myId,
                        sender = if (group != null && m.senderId != myId) nameOf(m.senderId) else null,
                        player = player,
                        quote = m.replyTo?.let { ref ->
                            nameOf(ref.senderId) to (quoted?.let(::previewText) ?: "Message not available")
                        },
                        highlighted = m.ref == highlighted,
                        onRetry = { repo.retry(m.clientId) },
                        onLongPress = { actionsFor = m },
                        onQuoteClick = {
                            val index = shown.indexOfFirst { it.ref == m.replyTo }
                            if (index >= 0) {
                                scope.launch {
                                    listState.animateScrollToItem(index)
                                    highlighted = m.replyTo
                                    delay(1_500)
                                    highlighted = null
                                }
                            }
                        },
                        onReactionsClick = {
                            if (myId != null && myId in m.reactions) repo.react(m, "") else actionsFor = m
                        },
                    )
                }
                // reverseLayout: the last item sits above the oldest message.
                item(key = "e2e-notice") {
                    EncryptionNotice(onClick = { if (group != null) openInfo(true) else showSafety = true })
                }
            }
            if (waitingForKeys && group == null) {
                SystemNotice("${peer?.displayName ?: "They"} hasn't opened NovaChat yet. Your messages will be sent as soon as they do.")
            }
            replyTo?.let { r ->
                ComposerBanner(nameOf(r.senderId), previewText(r), onClose = { replyTo = null })
            }
            editing?.let { e ->
                ComposerBanner("Editing message", previewText(e), onClose = { editing = null; draft = "" })
            }
            val g = group
            if (g == null && blocked) {
                Surface(
                    onClick = {
                        scope.launch {
                            try {
                                repo.unblock(peerId)
                            } catch (e: ApiException) {
                                toast(e.message)
                            }
                        }
                    },
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "You blocked this contact. Tap to unblock.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            } else if (g != null && !g.canSend(myId)) {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (g.left || g.member(myId) == null) "You're no longer in this group" else "Only admins can send messages",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            } else {
                MessageComposer(
                    text = draft,
                    editing = editing != null,
                    onText = {
                        draft = it
                        val now = System.currentTimeMillis()
                        if (it.isNotBlank() && now - lastTypingSent > 3_000) {
                            lastTypingSent = now
                            if (group == null) repo.sendTyping(peerId)
                        }
                    },
                    onSend = {
                        val e = editing
                        if (e != null) {
                            repo.edit(e, draft)
                            editing = null
                        } else {
                            repo.send(peerId, draft, replyTo?.ref)
                            replyTo = null
                        }
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
                            repo.sendAttachment(peerId, repo.media.prepareVoice(result.first, result.second), replyTo = replyTo?.ref)
                            replyTo = null
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: ChatMessage,
    mine: Boolean,
    sender: String?,
    player: VoicePlayer,
    quote: Pair<String, String>?,
    highlighted: Boolean,
    onRetry: () -> Unit,
    onLongPress: () -> Unit,
    onQuoteClick: () -> Unit,
    onReactionsClick: () -> Unit,
) {
    val colors = LocalBubbleColors.current
    // Rounded "pill" bubbles with one tighter corner pointing at the sender.
    val shape = if (mine) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
    }
    val bg = (if (mine) colors.mine else colors.theirs).let {
        if (highlighted) lerp(it, MaterialTheme.colorScheme.secondary, 0.35f) else it
    }
    val fg = if (mine) colors.onMine else colors.onTheirs
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Surface(
            color = bg,
            shape = shape,
            shadowElevation = if (mine) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            val attachment = m.attachment.takeUnless { m.undecryptable || m.deleted }
            val visual = attachment != null && (attachment.kind == AttachmentKind.image || attachment.kind == AttachmentKind.video)
            val pad = if (visual) {
                Modifier.padding(start = 5.dp, end = 5.dp, top = 5.dp, bottom = 6.dp)
            } else {
                Modifier.padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
            }
            // Inside the Surface, so the press ripple follows the bubble's shape.
            Column(
                Modifier
                    .combinedClickable(
                        onClick = { if (m.status == DeliveryStatus.Failed) onRetry() },
                        onLongClick = onLongPress,
                    )
                    .then(pad),
            ) {
                if (sender != null) {
                    Text(
                        sender,
                        color = nameColor(m.senderId),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = if (visual) 6.dp else 0.dp).padding(bottom = 2.dp),
                    )
                }
                if (m.forwarded && !m.deleted) {
                    Box(Modifier.padding(horizontal = if (visual) 6.dp else 0.dp)) { ForwardedLabel(fg) }
                }
                if (quote != null && !m.deleted) {
                    ReplyQuote(
                        quote.first,
                        quote.second,
                        accent = if (mine) fg else MaterialTheme.colorScheme.primary,
                        fg = fg,
                        modifier = Modifier.widthIn(min = 140.dp).padding(bottom = 6.dp),
                        onClick = onQuoteClick,
                    )
                }
                if (m.deleted) {
                    DeletedText(mine, fg)
                } else if (m.undecryptable) {
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
                    if (m.starred) {
                        Icon(Icons.Filled.Star, "Starred", Modifier.size(12.dp), tint = fg.copy(alpha = 0.7f))
                        Spacer(Modifier.width(4.dp))
                    }
                    if (m.editedAtMillis != null && !m.deleted) {
                        Text("edited  ", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f), fontStyle = FontStyle.Italic)
                    }
                    Text(formatTime(m.createdAtMillis), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                    if (mine) {
                        Spacer(Modifier.width(4.dp))
                        StatusTicks(m.status, tint = fg.copy(alpha = 0.7f), readTint = colors.readTick, size = 15.dp)
                    }
                }
            }
        }
        if (m.reactions.isNotEmpty() && !m.deleted) {
            Box(Modifier.offset(y = (-6).dp)) { ReactionsPill(m.reactions, mine, onReactionsClick) }
        }
    }
}

@Composable
private fun MessageComposer(
    text: String,
    editing: Boolean,
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
                    if (!editing) {
                        IconButton(onClick = onAttach, modifier = Modifier.padding(bottom = 2.dp)) {
                            Icon(painterResource(R.drawable.ic_attach), "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Spacer(Modifier.width(8.dp))
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
            onClick = {
                when {
                    recordingMs != null -> onSendRecording()
                    text.isNotBlank() -> onSend()
                    !editing -> onMic()
                }
            },
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.size(52.dp),
        ) {
            if (editing) {
                Icon(Icons.Filled.Check, "Save edit")
            } else if (sendNow) {
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
internal fun SafetyNumberDialog(peerName: String, load: suspend () -> String?, onDismiss: () -> Unit) {
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

/** A group event, such as "Ana added Ben", centred between the messages. */
@Composable
private fun SystemNotice(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

private val senderPalette = listOf(
    Color(0xFF5B3DF5), Color(0xFF19A99B), Color(0xFFE2577B), Color(0xFFF29B38),
    Color(0xFF3C8DEB), Color(0xFF9A4DD8), Color(0xFF2FA35A), Color(0xFFD1494A),
)

/** Each person's name has its own colour in group chats. */
private fun nameColor(id: String): Color = senderPalette[(id.hashCode() and Int.MAX_VALUE) % senderPalette.size]
