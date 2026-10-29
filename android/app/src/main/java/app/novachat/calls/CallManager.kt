package app.novachat.calls

import android.content.Context
import app.novachat.data.CallLog
import app.novachat.data.CallSignal
import app.novachat.data.ChatRepository
import app.novachat.data.IncomingCallSignal
import app.novachat.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID

enum class CallPhase {
    /** We called; their phone hasn't said it's ringing yet. */
    Calling,
    /** We called and their phone is ringing. */
    Ringing,
    /** They are calling us. */
    Incoming,
    Connecting,
    Connected,
    /** The network dropped for a moment; WebRTC is trying to get it back. */
    Reconnecting,
    /** Over: shown for a moment with [CallState.endReason]. */
    Ended,
}

data class CallState(
    val id: String,
    val peerId: String,
    val outgoing: Boolean,
    val phase: CallPhase,
    /** Started as a video call (for the call log and the ringing screen). */
    val startedAsVideo: Boolean,
    /** Our camera is on. */
    val video: Boolean,
    /** Their camera is on. */
    val remoteVideo: Boolean,
    val muted: Boolean = false,
    val speaker: Boolean,
    val frontCamera: Boolean = true,
    val connectedAtMillis: Long? = null,
    val endReason: String? = null,
)

/**
 * Runs one-to-one voice and video calls. The offer, answer and hang-up are
 * end-to-end encrypted messages (see [CallSignal]); the call itself goes
 * straight between the phones, through a TURN relay only when it must.
 * Everything here runs one step at a time on [lane].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallManager(
    private val context: Context,
    private val repo: ChatRepository,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
) {
    private val lane = Dispatchers.Default.limitedParallelism(1)

    private val _state = MutableStateFlow<CallState?>(null)
    val state: StateFlow<CallState?> = _state

    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    /** Our camera, while it is on. */
    val localVideo: StateFlow<VideoTrack?> = _localVideo

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    /** The other phone's video track, once it arrives. */
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo

    private val egl: EglBase by lazy { EglBase.create() }
    val eglContext: EglBase.Context get() = egl.eglBaseContext

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
        )
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(
                JavaAudioDeviceModule.builder(context.applicationContext)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule(),
            )
            .createPeerConnectionFactory()
    }

    private val audio = CallAudio(context)
    private var rtc: RtcSession? = null
    private var pendingOffer: String? = null
    private var timer: Job? = null

    init {
        scope.launch(lane) { repo.callSignals.collect { onSignal(it) } }
    }

    private fun act(block: suspend () -> Unit) {
        scope.launch(lane) { block() }
    }

    fun startCall(peerId: String, video: Boolean) = act {
        if (_state.value != null) return@act
        val id = UUID.randomUUID().toString()
        update(CallState(id, peerId, outgoing = true, phase = CallPhase.Calling, startedAsVideo = video, video = video, remoteVideo = false, speaker = video))
        CallService.start(context, peerName(peerId), video)
        audio.startCall(speaker = video)
        try {
            val session = newSession(id)
            if (video && !startCamera(session)) _state.value?.let { update(it.copy(video = false)) }
            val offer = session.createOffer()
            if (_state.value?.id != id) return@act // hung up while we were getting ready
            repo.sendCallSignal(peerId, CallSignal(id, "offer", video, offer))
        } catch (e: Exception) {
            finish(id, "Couldn't start the call", null)
            return@act
        }
        timer = scope.launch(lane) {
            delay(RING_TIMEOUT_MS)
            val st = _state.value
            if (st?.id == id && (st.phase == CallPhase.Calling || st.phase == CallPhase.Ringing)) {
                repo.sendCallSignal(peerId, CallSignal(id, "end"))
                finish(id, "No answer", OUTCOME_MISSED)
            }
        }
    }

    /**
     * Answers the ringing call, with our camera on if [video] (and it was a
     * video call). The app must already hold the microphone permission, and
     * the camera permission for video.
     */
    fun accept(video: Boolean) = act {
        val ringing = _state.value ?: return@act
        val offer = pendingOffer
        if (ringing.phase != CallPhase.Incoming || offer == null) return@act
        val st = ringing.copy(video = video && ringing.startedAsVideo)
        timer?.cancel()
        audio.stopRinging()
        notifier.cancelIncomingCall()
        update(st.copy(phase = CallPhase.Connecting))
        CallService.start(context, peerName(st.peerId), st.video)
        audio.startCall(speaker = st.speaker)
        try {
            val session = newSession(st.id)
            if (st.video && !startCamera(session)) _state.value?.let { update(it.copy(video = false)) }
            val answer = session.answer(offer)
            if (_state.value?.id != st.id) return@act
            repo.sendCallSignal(st.peerId, CallSignal(st.id, "answer", _state.value?.video == true, answer))
            watchConnecting(st.id)
        } catch (e: Exception) {
            repo.sendCallSignal(st.peerId, CallSignal(st.id, "end"))
            finish(st.id, "Couldn't connect", null)
        }
    }

    fun decline() = act {
        val st = _state.value ?: return@act
        if (st.phase != CallPhase.Incoming) return@act
        repo.sendCallSignal(st.peerId, CallSignal(st.id, "decline"))
        finish(st.id, "Call declined", null)
    }

    fun hangUp() = act {
        val st = _state.value ?: return@act
        when (st.phase) {
            CallPhase.Ended -> Unit
            CallPhase.Incoming -> {
                repo.sendCallSignal(st.peerId, CallSignal(st.id, "decline"))
                finish(st.id, "Call declined", null)
            }
            else -> {
                repo.sendCallSignal(st.peerId, CallSignal(st.id, "end"))
                finish(st.id, "Call ended", OUTCOME_MISSED)
            }
        }
    }

    fun setMuted(muted: Boolean) = act {
        val st = _state.value ?: return@act
        rtc?.setMuted(muted)
        update(st.copy(muted = muted))
    }

    fun setSpeaker(on: Boolean) = act {
        val st = _state.value ?: return@act
        audio.setSpeaker(on)
        update(st.copy(speaker = on))
    }

    /** Turns our camera on or off. The app must already hold the camera permission to turn it on. */
    fun setVideo(on: Boolean) = act {
        val st = _state.value ?: return@act
        val session = rtc ?: return@act
        if (st.phase == CallPhase.Incoming || st.phase == CallPhase.Ended) return@act
        if (on) {
            if (!startCamera(session)) return@act
        } else {
            session.setCamera(false)
            _localVideo.value = null
        }
        // Video calls belong on the speaker, like holding the phone in front of you.
        if (on && !st.speaker) audio.setSpeaker(true)
        CallService.start(context, peerName(st.peerId), on)
        update(st.copy(video = on, speaker = st.speaker || on, frontCamera = session.frontCamera))
        repo.sendCallSignal(st.peerId, CallSignal(st.id, "video", video = on))
    }

    fun switchCamera() = act {
        rtc?.switchCamera { front ->
            act { _state.value?.let { update(it.copy(frontCamera = front)) } }
        }
    }

    private fun startCamera(session: RtcSession): Boolean {
        val ok = try {
            session.setCamera(true)
        } catch (e: RuntimeException) {
            false
        }
        if (ok) _localVideo.value = session.videoTrack
        return ok
    }

    private suspend fun newSession(id: String): RtcSession {
        rtc?.close()
        val servers = repo.iceServers()
        return RtcSession(
            context = context,
            factory = factory,
            egl = egl,
            iceServers = servers,
            onConnection = { state -> act { onConnection(id, state) } },
            onRemoteVideo = { track -> _remoteVideo.value = track },
        ).also { rtc = it }
    }

    private suspend fun onSignal(incoming: IncomingCallSignal) {
        val s = incoming.signal
        val st = _state.value
        if (s.type == "offer") {
            // An old offer from a catch-up after being offline: that call is long over.
            if (System.currentTimeMillis() - incoming.sentAtMillis > OFFER_FRESH_MS) return
            if (st != null) {
                if (st.id != s.id) repo.sendCallSignal(incoming.from, CallSignal(s.id, "busy"))
                return
            }
            val sdp = s.sdp ?: return
            pendingOffer = sdp
            update(
                CallState(
                    id = s.id,
                    peerId = incoming.from,
                    outgoing = false,
                    phase = CallPhase.Incoming,
                    startedAsVideo = s.video,
                    video = s.video,
                    remoteVideo = s.video,
                    speaker = s.video,
                ),
            )
            val person = repo.person(incoming.from)
            notifier.showIncomingCall(person?.displayName ?: "Someone", person?.avatarId, s.video)
            audio.startRinging()
            repo.sendCallSignal(incoming.from, CallSignal(s.id, "ringing"))
            timer = scope.launch(lane) {
                delay(RING_TIMEOUT_MS + RING_GRACE_MS)
                if (_state.value?.let { it.id == s.id && it.phase == CallPhase.Incoming } == true) {
                    finish(s.id, "Missed call", null)
                }
            }
            return
        }
        if (st == null || st.id != s.id || incoming.from != st.peerId) return
        when (s.type) {
            "ringing" -> if (st.phase == CallPhase.Calling) {
                update(st.copy(phase = CallPhase.Ringing))
                audio.startRingback()
            }
            "answer" -> if (st.outgoing && (st.phase == CallPhase.Calling || st.phase == CallPhase.Ringing)) {
                val sdp = s.sdp ?: return
                timer?.cancel()
                audio.stopRingback()
                update(st.copy(phase = CallPhase.Connecting, remoteVideo = s.video))
                try {
                    rtc?.applyAnswer(sdp)
                    watchConnecting(st.id)
                } catch (e: Exception) {
                    repo.sendCallSignal(st.peerId, CallSignal(st.id, "end"))
                    finish(st.id, "Couldn't connect", OUTCOME_MISSED)
                }
            }
            "decline" -> finish(st.id, "Call declined", OUTCOME_DECLINED)
            "busy" -> finish(st.id, "On another call", OUTCOME_BUSY)
            "end" -> finish(st.id, if (st.phase == CallPhase.Incoming) "Missed call" else "Call ended", OUTCOME_MISSED)
            "video" -> update(st.copy(remoteVideo = s.video))
        }
    }

    private suspend fun onConnection(id: String, state: PeerConnection.PeerConnectionState) {
        val st = _state.value ?: return
        if (st.id != id || st.phase == CallPhase.Ended) return
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                timer?.cancel()
                update(st.copy(phase = CallPhase.Connected, connectedAtMillis = st.connectedAtMillis ?: System.currentTimeMillis()))
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> if (st.phase == CallPhase.Connected) {
                update(st.copy(phase = CallPhase.Reconnecting))
                timer = scope.launch(lane) {
                    delay(RECONNECT_TIMEOUT_MS)
                    if (_state.value?.let { it.id == id && it.phase == CallPhase.Reconnecting } == true) {
                        repo.sendCallSignal(st.peerId, CallSignal(id, "end"))
                        finish(id, "Connection lost", OUTCOME_MISSED)
                    }
                }
            }
            PeerConnection.PeerConnectionState.FAILED -> {
                repo.sendCallSignal(st.peerId, CallSignal(id, "end"))
                finish(id, if (st.connectedAtMillis != null) "Connection lost" else "Couldn't connect", OUTCOME_MISSED)
            }
            else -> Unit
        }
    }

    /** Gives up if the phones can't reach each other in time. */
    private fun watchConnecting(id: String) {
        timer?.cancel()
        timer = scope.launch(lane) {
            delay(CONNECT_TIMEOUT_MS)
            val st = _state.value
            if (st?.id == id && st.phase == CallPhase.Connecting) {
                repo.sendCallSignal(st.peerId, CallSignal(id, "end"))
                finish(id, "Couldn't connect", OUTCOME_MISSED)
            }
        }
    }

    /**
     * Ends call [id]: frees the camera, microphone and connection, and (for
     * the caller) adds the call to the chat. [outcome] is what the log says
     * if the call never connected; null adds no log.
     */
    private suspend fun finish(id: String, reason: String, outcome: String?) {
        val st = _state.value ?: return
        if (st.id != id || st.phase == CallPhase.Ended) return
        timer?.cancel()
        audio.stopCall()
        notifier.cancelIncomingCall()
        _localVideo.value = null
        _remoteVideo.value = null
        rtc?.close()
        rtc = null
        pendingOffer = null
        CallService.stop(context)
        if (st.outgoing) repo.dropUnsentCallOffers(st.peerId)
        update(st.copy(phase = CallPhase.Ended, endReason = reason))
        if (st.outgoing) {
            val connectedAt = st.connectedAtMillis
            val log = when {
                connectedAt != null -> CallLog(st.startedAsVideo, OUTCOME_ANSWERED, ((System.currentTimeMillis() - connectedAt) / 1000).toInt())
                outcome != null -> CallLog(st.startedAsVideo, outcome)
                else -> null
            }
            log?.let { repo.sendCallLog(st.peerId, it) }
        }
        scope.launch(lane) {
            delay(ENDED_SHOWN_MS)
            if (_state.value?.id == id) _state.value = null
        }
    }

    private fun update(next: CallState) {
        _state.value = next
        // Screen off near the ear, only for voice calls on the earpiece.
        val live = next.phase != CallPhase.Incoming && next.phase != CallPhase.Ended
        audio.setProximity(live && !next.speaker && !next.video)
    }

    private suspend fun peerName(peerId: String): String = repo.person(peerId)?.displayName ?: "NovaChat call"

    private companion object {
        const val RING_TIMEOUT_MS = 45_000L
        const val RING_GRACE_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val RECONNECT_TIMEOUT_MS = 20_000L
        const val ENDED_SHOWN_MS = 1_800L
        const val OFFER_FRESH_MS = 60_000L
        const val OUTCOME_ANSWERED = "answered"
        const val OUTCOME_MISSED = "missed"
        const val OUTCOME_DECLINED = "declined"
        const val OUTCOME_BUSY = "busy"
    }
}
