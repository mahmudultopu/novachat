package app.novachat.ui.call

import android.Manifest
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.R
import app.novachat.SessionState
import app.novachat.calls.CallPhase
import app.novachat.calls.MemberPhase
import app.novachat.calls.CallState
import app.novachat.data.User
import app.novachat.ui.appContainer
import app.novachat.ui.chat.formatDuration
import app.novachat.ui.common.Avatar
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private val CallBackground = Brush.verticalGradient(listOf(Color(0xFF1B1F3B), Color(0xFF0E1020)))
private val EndRed = Color(0xFFE5484D)
private val AnswerGreen = Color(0xFF2EB67D)

/**
 * The call, full screen over the rest of the app. [answerRequested] is set
 * when "Answer" was tapped on the notification.
 */
@Composable
fun CallScreen(call: CallState, answerRequested: Boolean, onAnswerHandled: () -> Unit) {
    val context = LocalContext.current
    val container = appContainer()
    val calls = container.calls
    val people by container.chats.people.collectAsStateWithLifecycle(initialValue = emptyMap())
    val groups by container.chats.groups.collectAsStateWithLifecycle(initialValue = emptyMap())
    val peer = people[call.peerId]
    val group = if (call.group) groups[call.peerId] else null
    val name = if (call.group) group?.name ?: "Group call" else peer?.displayName ?: "…"
    val avatarId = if (call.group) group?.avatarId else peer?.avatarId
    val local by calls.localVideo.collectAsStateWithLifecycle()
    val remote by calls.remoteVideo.collectAsStateWithLifecycle()
    val memberVideo by calls.memberVideo.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()
    val me = (session as? SessionState.SignedIn)?.session?.user
    val live = call.phase == CallPhase.Connected || call.phase == CallPhase.Reconnecting
    val showRemote = !call.group && remote != null && call.remoteVideo && live
    // Group calls show everyone in a grid once we're in the call.
    val showGrid = call.group && call.phase != CallPhase.Incoming && call.phase != CallPhase.Ended
    var controlsVisible by remember { mutableStateOf(true) }

    // Back leaves the app with the call still going, like pressing Home.
    BackHandler { (context as? Activity)?.moveTaskToBack(true) }

    // Keep the screen on for video; voice calls use the proximity sensor instead.
    val view = LocalView.current
    val anyVideo = call.video || call.remoteVideo || call.members.any { it.video }
    DisposableEffect(anyVideo) {
        view.keepScreenOn = anyVideo
        onDispose { view.keepScreenOn = false }
    }

    val answerPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (context.has(Manifest.permission.RECORD_AUDIO)) {
            calls.accept(video = context.has(Manifest.permission.CAMERA))
        } else {
            Toast.makeText(context, "NovaChat needs the microphone to answer calls", Toast.LENGTH_SHORT).show()
            calls.decline()
        }
    }
    fun answer() {
        val needed = listOfNotNull(Manifest.permission.RECORD_AUDIO, if (call.startedAsVideo) Manifest.permission.CAMERA else null)
            .filterNot { context.has(it) }
        if (needed.isEmpty()) calls.accept(video = call.startedAsVideo) else answerPermissions.launch(needed.toTypedArray())
    }
    LaunchedEffect(answerRequested, call.id) {
        if (answerRequested && call.phase == CallPhase.Incoming) answer()
        if (answerRequested) onAnswerHandled()
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) calls.setVideo(true) else Toast.makeText(context, "NovaChat needs the camera for video", Toast.LENGTH_SHORT).show()
    }
    fun toggleVideo() {
        when {
            call.video -> calls.setVideo(false)
            context.has(Manifest.permission.CAMERA) -> calls.setVideo(true)
            else -> cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CallBackground)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (showRemote) controlsVisible = !controlsVisible
            },
    ) {
        val bigTrack = if (showGrid) null else if (showRemote) remote else local
        bigTrack?.let { track ->
            VideoView(track, calls.eglContext, mirror = track === local && call.frontCamera, modifier = Modifier.fillMaxSize())
        }
        // Our camera in the corner once their video fills the screen.
        val smallTrack = local
        if (showRemote && smallTrack != null) {
            VideoView(
                smallTrack,
                calls.eglContext,
                mirror = call.frontCamera,
                overlay = true,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(16.dp)
                    .size(width = 108.dp, height = 156.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
        }

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!showRemote || controlsVisible) {
                Text(
                    "🔒 End-to-end encrypted",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(if (bigTrack == null && !showGrid) 48.dp else 12.dp))
                if (bigTrack == null && !showGrid) {
                    Avatar(name, call.peerId, 120.dp, avatarId)
                    Spacer(Modifier.height(20.dp))
                }
                Text(name, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                val duration = rememberCallDuration(call.connectedAtMillis.takeIf { call.phase == CallPhase.Connected })
                val status = if (call.group) groupStatusText(call, people) else statusText(call)
                Text(
                    when {
                        duration.isEmpty() -> status
                        call.group -> "$duration · $status"
                        else -> duration
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
            if (showGrid) {
                val tiles = groupCallTiles(me?.id, me, call.members, people, memberVideo, local, call.frontCamera)
                GroupCallGrid(tiles, calls.eglContext, Modifier.weight(1f).fillMaxWidth())
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (call.phase == CallPhase.Incoming) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    RoundButton(R.drawable.ic_call_end, "Decline", EndRed, label = "Decline", onClick = calls::decline)
                    RoundButton(
                        if (call.startedAsVideo) R.drawable.ic_videocam else R.drawable.ic_call,
                        "Answer",
                        AnswerGreen,
                        label = "Answer",
                        onClick = ::answer,
                    )
                }
            } else if (call.phase != CallPhase.Ended && (!showRemote || controlsVisible)) {
                Surface(shape = RoundedCornerShape(32.dp), color = Color.Black.copy(alpha = 0.35f)) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToggleButton(R.drawable.ic_speaker, "Speaker", call.speaker) { calls.setSpeaker(!call.speaker) }
                        ToggleButton(
                            if (call.video) R.drawable.ic_videocam else R.drawable.ic_videocam_off,
                            if (call.video) "Turn camera off" else "Turn camera on",
                            call.video,
                            ::toggleVideo,
                        )
                        if (call.video) ToggleButton(R.drawable.ic_flip_camera, "Switch camera", false, calls::switchCamera)
                        ToggleButton(
                            if (call.muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                            if (call.muted) "Unmute" else "Mute",
                            call.muted,
                        ) { calls.setMuted(!call.muted) }
                        RoundButton(R.drawable.ic_call_end, "Hang up", EndRed, size = 56, onClick = calls::hangUp)
                    }
                }
            }
        }
    }
}

/** "Ana is calling", "Calling everyone…", "3 people" and so on. */
private fun groupStatusText(call: CallState, people: Map<String, User>): String {
    val first = { id: String? -> people[id]?.displayName?.substringBefore(' ') ?: "Someone" }
    val joined = call.members.count { it.phase == MemberPhase.Connected }
    return when (call.phase) {
        CallPhase.Incoming -> {
            val kind = if (call.startedAsVideo) "group video call" else "group voice call"
            "${first(call.callerId)} invites you to a $kind"
        }
        CallPhase.Calling, CallPhase.Ringing -> "Calling everyone…"
        CallPhase.Connecting -> "Connecting…"
        CallPhase.Connected, CallPhase.Reconnecting -> if (joined == 1) "2 people" else "${joined + 1} people"
        CallPhase.Ended -> call.endReason ?: "Call ended"
    }
}

private fun statusText(call: CallState): String = when (call.phase) {
    CallPhase.Calling -> "Calling…"
    CallPhase.Ringing -> "Ringing…"
    CallPhase.Incoming -> if (call.startedAsVideo) "Incoming video call" else "Incoming voice call"
    CallPhase.Connecting -> "Connecting…"
    CallPhase.Connected -> "In call"
    CallPhase.Reconnecting -> "Reconnecting…"
    CallPhase.Ended -> call.endReason ?: "Call ended"
}

@Composable
private fun RoundButton(
    icon: Int,
    description: String,
    color: Color,
    label: String? = null,
    size: Int = 68,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, shape = CircleShape, color = color, modifier = Modifier.size(size.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), description, tint = Color.White, modifier = Modifier.size((size * 0.42f).dp))
            }
        }
        if (label != null) {
            Spacer(Modifier.height(8.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ToggleButton(icon: Int, description: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (on) Color.White else Color.White.copy(alpha = 0.16f),
        modifier = Modifier.size(52.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), description, tint = if (on) Color(0xFF1B1F3B) else Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

/** Shows a WebRTC video track. */
@Composable
internal fun VideoView(
    track: VideoTrack,
    egl: EglBase.Context,
    mirror: Boolean,
    modifier: Modifier,
    overlay: Boolean = false,
) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (overlay) setZOrderMediaOverlay(true)
                renderer = this
            }
        },
        update = { it.setMirror(mirror) },
        onRelease = { it.release() },
        modifier = modifier,
    )
    DisposableEffect(track, renderer) {
        val sink = renderer
        if (sink != null) runCatching { track.addSink(sink) }
        onDispose {
            // The track may already be gone with the call.
            if (sink != null) runCatching { track.removeSink(sink) }
        }
    }
}

/** The call's running time, ticking every second. */
@Composable
fun rememberCallDuration(connectedAtMillis: Long?): String {
    val text by produceState("", connectedAtMillis) {
        if (connectedAtMillis == null) value = ""
        while (connectedAtMillis != null) {
            value = formatDuration(System.currentTimeMillis() - connectedAtMillis)
            delay(1_000)
        }
    }
    return text
}
