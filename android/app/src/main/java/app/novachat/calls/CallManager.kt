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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /** We called; nobody's phone has said it's ringing yet. */
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

/** How far along our connection to one person in a group call is. */
enum class MemberPhase { Invited, Connecting, Connected, Reconnecting }

/** Someone else in a group call. */
data class CallMember(val id: String, val phase: MemberPhase, val video: Boolean)

data class CallState(
    val id: String,
    /** The other person, or the group for a group call. */
    val peerId: String,
    val outgoing: Boolean,
    val phase: CallPhase,
    /** Started as a video call (for the call log and the ringing screen). */
    val startedAsVideo: Boolean,
    /** Our camera is on. */
    val video: Boolean,
    /** One-to-one calls: their camera is on. */
    val remoteVideo: Boolean,
    val muted: Boolean = false,
    val speaker: Boolean,
    val frontCamera: Boolean = true,
    val connectedAtMillis: Long? = null,
    val endReason: String? = null,
    /** A group call, in the group [peerId]. */
    val group: Boolean = false,
    /** Group calls: who started it. */
    val callerId: String? = null,
    /** Group calls: everyone else we're connected to or calling. */
    val members: List<CallMember> = emptyList(),
)

/**
 * Runs voice and video calls. The offer, answer and hang-up are end-to-end
 * encrypted messages (see [CallSignal]); the call itself goes straight
 * between the phones, through a TURN relay only when it must.
 *
 * A group call is a mesh: every two people in it have their own connection.
 * Whoever joins sends an offer to everyone in the group they aren't
 * connected to yet, and answers the offers waiting for them. People who
 * haven't answered yet collect those offers while their phone rings.
 *
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
    /** One-to-one calls: the other phone's video track, once it arrives. */
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo

    private val _memberVideo = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    /** Group calls: each person's video track, by user ID. */
    val memberVideo: StateFlow<Map<String, VideoTrack>> = _memberVideo

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

    /** Our connection (or, while ringing, the waiting offer) for one other person. */
    private class Link(val peerId: String) {
        var session: RtcSession? = null
        /** Their offer, waiting for us to answer. */
        var offer: String? = null
        /** We sent the offer, so we wait for their answer. */
        var weOffered = false
        var phase = MemberPhase.Invited
        var video = false
        /** Gives up on this person if they don't answer or connect in time. */
        var watch: Job? = null
    }

    private val audio = CallAudio(context)
    private var media: LocalMedia? = null
    private val links = LinkedHashMap<String, Link>()
    private var timer: Job? = null

    init {
        scope.launch(lane) { repo.callSignals.collect { onSignal(it) } }
    }

    private fun act(block: suspend () -> Unit) {
        scope.launch(lane) { block() }
    }

    /** Calls a person, or everyone in a group when [peerId] is a group. */
    fun startCall(peerId: String, video: Boolean) = act {
        if (_state.value != null) return@act
        val group = repo.groupNow(peerId)
        if (group != null) return@act startGroupCall(peerId, video)
        val id = UUID.randomUUID().toString()
        update(CallState(id, peerId, outgoing = true, phase = CallPhase.Calling, startedAsVideo = video, video = video, remoteVideo = false, speaker = video))
        CallService.start(context, peerName(peerId), video)
        audio.startCall(speaker = video)
        try {
            val m = newMedia(group = false)
            if (video && !startCamera(m)) _state.value?.let { update(it.copy(video = false)) }
            if (!offerTo(id, peerId, groupId = null)) return@act
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

    private suspend fun startGroupCall(groupId: String, video: Boolean) {
        val me = repo.signedInUserId ?: return
        val group = repo.groupNow(groupId) ?: return
        if (group.left || group.member(me) == null) return
        val others = group.members.map { it.userId }.filter { it != me }
        if (others.isEmpty()) return
        val id = UUID.randomUUID().toString()
        update(
            CallState(
                id, groupId, outgoing = true, phase = CallPhase.Calling, startedAsVideo = video, video = video,
                remoteVideo = false, speaker = true, group = true, callerId = me,
            ),
        )
        CallService.start(context, group.name, video)
        audio.startCall(speaker = true)
        try {
            val m = newMedia(group = true)
            if (video && !startCamera(m)) _state.value?.let { update(it.copy(video = false)) }
            // Ring at most MAX_GROUP_CALL - 1 people; the rest can't join this call.
            offerToAll(id, groupId, others.take(MAX_GROUP_CALL - 1))
        } catch (e: Exception) {
            finish(id, "Couldn't start the call", null)
        }
    }

    /** Sends an offer to each of [people] at once (each one gathers its network routes for a few seconds). */
    private suspend fun offerToAll(id: String, groupId: String, people: List<String>) = coroutineScope {
        people.map { person ->
            async {
                try {
                    offerTo(id, person, groupId)
                } catch (e: Exception) {
                    dropLink(person)
                }
            }
        }.awaitAll()
        syncMembers()
    }

    /** Opens a connection to [peerId] and sends them our offer. Returns false if the call ended meanwhile. */
    private suspend fun offerTo(id: String, peerId: String, groupId: String?): Boolean {
        val link = Link(peerId).also { links[peerId] = it }
        val session = newSession(id, peerId)
        link.session = session
        link.weOffered = true
        if (groupId != null) watchLink(id, link, RING_TIMEOUT_MS)
        val sdp = session.createOffer()
        if (_state.value?.id != id || links[peerId] !== link) return false // hung up while we were getting ready
        repo.sendCallSignal(peerId, CallSignal(id, "offer", media?.cameraOn == true, sdp, groupId))
        return true
    }

    /**
     * Answers the ringing call, with our camera on if [video] (and it was a
     * video call). The app must already hold the microphone permission, and
     * the camera permission for video.
     */
    fun accept(video: Boolean) = act {
        val ringing = _state.value ?: return@act
        if (ringing.phase != CallPhase.Incoming || links.values.none { it.offer != null }) return@act
        val st = ringing.copy(video = video && ringing.startedAsVideo)
        timer?.cancel()
        audio.stopRinging()
        notifier.cancelIncomingCall()
        update(st.copy(phase = CallPhase.Connecting))
        CallService.start(context, callName(st), st.video)
        audio.startCall(speaker = st.speaker)
        try {
            val m = newMedia(group = st.group)
            if (st.video && !startCamera(m)) _state.value?.let { update(it.copy(video = false)) }
            if (!st.group) {
                val link = links[st.peerId] ?: return@act
                answer(st.id, link, groupId = null)
                watchConnecting(st.id)
                return@act
            }
            for (link in links.values.toList()) {
                if (link.offer != null) {
                    try {
                        answer(st.id, link, st.peerId)
                    } catch (e: Exception) {
                        dropLink(link.peerId)
                    }
                }
            }
            // Then call everyone in the group nobody has connected us to yet.
            val me = repo.signedInUserId
            val group = repo.groupNow(st.peerId)
            if (_state.value?.id == st.id && me != null && group != null) {
                val room = MAX_GROUP_CALL - 1 - links.size
                val rest = group.members.map { it.userId }.filter { it != me && it !in links }.take(room.coerceAtLeast(0))
                offerToAll(st.id, st.peerId, rest)
            }
            syncMembers()
        } catch (e: Exception) {
            links.keys.toList().forEach { repo.sendCallSignal(it, CallSignal(st.id, "end", group = groupOf(st))) }
            finish(st.id, "Couldn't connect", null)
        }
    }

    /** Answers the offer waiting in [link]. */
    private suspend fun answer(id: String, link: Link, groupId: String?) {
        val offer = link.offer ?: return
        link.offer = null
        val session = newSession(id, link.peerId)
        link.session = session
        link.weOffered = false
        link.phase = MemberPhase.Connecting
        if (groupId != null) watchLink(id, link, CONNECT_TIMEOUT_MS)
        val sdp = session.answer(offer)
        if (_state.value?.id != id || links[link.peerId] !== link) return
        repo.sendCallSignal(link.peerId, CallSignal(id, "answer", media?.cameraOn == true, sdp, groupId))
    }

    fun decline() = act {
        val st = _state.value ?: return@act
        if (st.phase != CallPhase.Incoming) return@act
        links.keys.toList().forEach { repo.sendCallSignal(it, CallSignal(st.id, "decline", group = groupOf(st))) }
        finish(st.id, "Call declined", null)
    }

    fun hangUp() = act {
        val st = _state.value ?: return@act
        when (st.phase) {
            CallPhase.Ended -> Unit
            CallPhase.Incoming -> {
                links.keys.toList().forEach { repo.sendCallSignal(it, CallSignal(st.id, "decline", group = groupOf(st))) }
                finish(st.id, "Call declined", null)
            }
            else -> {
                links.keys.toList().forEach { repo.sendCallSignal(it, CallSignal(st.id, "end", group = groupOf(st))) }
                finish(st.id, if (st.group) "You left the call" else "Call ended", OUTCOME_MISSED)
            }
        }
    }

    fun setMuted(muted: Boolean) = act {
        val st = _state.value ?: return@act
        media?.setMuted(muted)
        links.values.forEach { it.session?.setMuted(muted) }
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
        val m = media ?: return@act
        if (st.phase == CallPhase.Incoming || st.phase == CallPhase.Ended) return@act
        if (on) {
            if (!startCamera(m)) return@act
        } else {
            m.setCamera(false)
            links.values.forEach { it.session?.setVideo(false) }
            _localVideo.value = null
        }
        // Video calls belong on the speaker, like holding the phone in front of you.
        if (on && !st.speaker) audio.setSpeaker(true)
        CallService.start(context, callName(st), on)
        update(st.copy(video = on, speaker = st.speaker || on, frontCamera = m.frontCamera))
        links.values.filter { it.session != null }.forEach {
            repo.sendCallSignal(it.peerId, CallSignal(st.id, "video", video = on, group = groupOf(st)))
        }
    }

    fun switchCamera() = act {
        media?.switchCamera { front ->
            act { _state.value?.let { update(it.copy(frontCamera = front)) } }
        }
    }

    private fun startCamera(m: LocalMedia): Boolean {
        val ok = try {
            m.setCamera(true)
        } catch (e: RuntimeException) {
            false
        }
        if (ok) {
            links.values.forEach { it.session?.setVideo(true) }
            _localVideo.value = m.preview
        }
        return ok
    }

    private fun newMedia(group: Boolean): LocalMedia {
        media?.close()
        return LocalMedia(context, factory, egl, small = group).also { media = it }
    }

    private suspend fun newSession(id: String, peerId: String): RtcSession {
        val m = media ?: throw IllegalStateException("No call media")
        val servers = repo.iceServers()
        lateinit var session: RtcSession
        session = RtcSession(
            factory = factory,
            media = m,
            iceServers = servers,
            onConnection = { state -> act { onConnection(id, peerId, session, state) } },
            onRemoteVideo = { track ->
                act {
                    if (links[peerId]?.session !== session) return@act
                    if (_state.value?.group == true) _memberVideo.value = _memberVideo.value + (peerId to track) else _remoteVideo.value = track
                }
            },
        )
        session.setMuted(m.muted)
        session.setVideo(m.cameraOn)
        return session
    }

    private suspend fun onSignal(incoming: IncomingCallSignal) {
        val s = incoming.signal
        val from = incoming.from
        val st = _state.value
        if (s.group != null) return onGroupSignal(incoming)
        if (s.type == "offer") {
            // An old offer from a catch-up after being offline: that call is long over.
            if (System.currentTimeMillis() - incoming.sentAtMillis > OFFER_FRESH_MS) return
            if (st != null) {
                if (st.id != s.id) repo.sendCallSignal(from, CallSignal(s.id, "busy"))
                return
            }
            val sdp = s.sdp ?: return
            links.clear()
            links[from] = Link(from).apply {
                offer = sdp
                video = s.video
            }
            update(
                CallState(
                    id = s.id,
                    peerId = from,
                    outgoing = false,
                    phase = CallPhase.Incoming,
                    startedAsVideo = s.video,
                    video = s.video,
                    remoteVideo = s.video,
                    speaker = s.video,
                ),
            )
            val person = repo.person(from)
            notifier.showIncomingCall(person?.displayName ?: "Someone", person?.avatarId, s.video)
            audio.startRinging()
            repo.sendCallSignal(from, CallSignal(s.id, "ringing"))
            ringIncoming(s.id)
            return
        }
        if (st == null || st.group || st.id != s.id || from != st.peerId) return
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
                    links[from]?.session?.applyAnswer(sdp)
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

    private suspend fun onGroupSignal(incoming: IncomingCallSignal) {
        val s = incoming.signal
        val from = incoming.from
        val groupId = s.group ?: return
        val me = repo.signedInUserId ?: return
        // Only people in the group can call us in it.
        val group = repo.groupNow(groupId) ?: return
        if (group.left || group.member(me) == null || group.member(from) == null) return
        val st = _state.value

        if (s.type == "offer") {
            if (System.currentTimeMillis() - incoming.sentAtMillis > OFFER_FRESH_MS) return
            val sdp = s.sdp ?: return
            when {
                st == null -> {
                    links.clear()
                    links[from] = Link(from).apply {
                        offer = sdp
                        video = s.video
                    }
                    update(
                        CallState(
                            id = s.id, peerId = groupId, outgoing = false, phase = CallPhase.Incoming,
                            startedAsVideo = s.video, video = s.video, remoteVideo = false, speaker = true,
                            group = true, callerId = from,
                        ),
                    )
                    syncMembers()
                    val caller = repo.person(from)?.displayName ?: "Someone"
                    notifier.showIncomingCall("$caller · ${group.name}", null, s.video)
                    audio.startRinging()
                    repo.sendCallSignal(from, CallSignal(s.id, "ringing", group = groupId))
                    ringIncoming(s.id)
                }
                st.id != s.id -> repo.sendCallSignal(from, CallSignal(s.id, "busy", group = groupId))
                st.phase == CallPhase.Ended -> Unit
                st.phase == CallPhase.Incoming -> {
                    // Someone else joined while we're still ringing: keep their offer too.
                    links[from] = (links[from] ?: Link(from)).apply {
                        offer = sdp
                        video = s.video
                    }
                    repo.sendCallSignal(from, CallSignal(s.id, "ringing", group = groupId))
                    syncMembers()
                }
                else -> {
                    val link = links[from]
                    // We both sent offers at once: the lower user ID's offer wins.
                    if (link?.weOffered == true && link.phase == MemberPhase.Invited && me < from) return
                    if (link != null && link.phase != MemberPhase.Invited && !link.weOffered) return // already connected
                    if (link == null && links.size >= MAX_GROUP_CALL - 1) {
                        repo.sendCallSignal(from, CallSignal(s.id, "busy", group = groupId))
                        return
                    }
                    if (link != null) closeLink(link)
                    val fresh = Link(from).apply {
                        offer = sdp
                        video = s.video
                    }
                    links[from] = fresh
                    try {
                        answer(st.id, fresh, groupId)
                    } catch (e: Exception) {
                        dropLink(from)
                    }
                    syncMembers()
                }
            }
            return
        }

        if (st == null || !st.group || st.id != s.id) return
        val link = links[from]
        when (s.type) {
            "ringing" -> if (st.phase == CallPhase.Calling) {
                update(st.copy(phase = CallPhase.Ringing))
                if (st.outgoing) audio.startRingback()
            }
            "answer" -> {
                val sdp = s.sdp ?: return
                if (link == null || !link.weOffered || link.phase != MemberPhase.Invited) return
                link.phase = MemberPhase.Connecting
                link.video = s.video
                watchLink(st.id, link, CONNECT_TIMEOUT_MS)
                if (st.phase == CallPhase.Calling || st.phase == CallPhase.Ringing) {
                    audio.stopRingback()
                    update(st.copy(phase = CallPhase.Connecting))
                }
                try {
                    link.session?.applyAnswer(sdp)
                } catch (e: Exception) {
                    repo.sendCallSignal(from, CallSignal(st.id, "end", group = groupId))
                    dropLink(from)
                    endIfAlone(st.id)
                }
                syncMembers()
            }
            "decline", "busy", "end" -> {
                if (link == null) return
                dropLink(from)
                if (st.phase == CallPhase.Incoming) {
                    if (links.isEmpty()) finish(st.id, "Missed call", null)
                } else {
                    endIfAlone(st.id)
                }
            }
            "video" -> if (link != null) {
                link.video = s.video
                syncMembers()
            }
        }
    }

    private suspend fun onConnection(id: String, peerId: String, session: RtcSession, state: PeerConnection.PeerConnectionState) {
        val st = _state.value ?: return
        if (st.id != id || st.phase == CallPhase.Ended) return
        val link = links[peerId]?.takeIf { it.session === session } ?: return
        if (st.group) return onGroupConnection(st, link, state)
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

    private suspend fun onGroupConnection(st: CallState, link: Link, state: PeerConnection.PeerConnectionState) {
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                link.watch?.cancel()
                link.phase = MemberPhase.Connected
                audio.stopRingback()
                update(st.copy(phase = CallPhase.Connected, connectedAtMillis = st.connectedAtMillis ?: System.currentTimeMillis()))
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> if (link.phase == MemberPhase.Connected) {
                link.phase = MemberPhase.Reconnecting
                watchLink(st.id, link, RECONNECT_TIMEOUT_MS)
            }
            PeerConnection.PeerConnectionState.FAILED -> {
                repo.sendCallSignal(link.peerId, CallSignal(st.id, "end", group = st.peerId))
                dropLink(link.peerId)
                endIfAlone(st.id)
            }
            else -> Unit
        }
        syncMembers()
    }

    /** Rings for an incoming call, then gives up. */
    private fun ringIncoming(id: String) {
        timer?.cancel()
        timer = scope.launch(lane) {
            delay(RING_TIMEOUT_MS + RING_GRACE_MS)
            if (_state.value?.let { it.id == id && it.phase == CallPhase.Incoming } == true) {
                finish(id, "Missed call", null)
            }
        }
    }

    /** Group calls: hangs up on one person who didn't answer, connect or come back within [timeoutMs]. */
    private fun watchLink(id: String, link: Link, timeoutMs: Long) {
        link.watch?.cancel()
        val phase = link.phase
        link.watch = scope.launch(lane) {
            delay(timeoutMs)
            if (_state.value?.id != id || links[link.peerId] !== link || link.phase != phase) return@launch
            repo.sendCallSignal(link.peerId, CallSignal(id, "end", group = _state.value?.peerId))
            dropLink(link.peerId)
            endIfAlone(id)
        }
    }

    /** Group calls: ends the call once nobody else is in it or still being called. */
    private suspend fun endIfAlone(id: String) {
        val st = _state.value ?: return
        if (st.id != id || st.phase == CallPhase.Ended || links.isNotEmpty()) return
        finish(id, if (st.connectedAtMillis == null) "No answer" else "Everyone left", OUTCOME_MISSED)
    }

    private fun closeLink(link: Link) {
        link.watch?.cancel()
        _memberVideo.value = _memberVideo.value - link.peerId
        link.session?.close()
        link.session = null
    }

    private fun dropLink(peerId: String) {
        val link = links.remove(peerId) ?: return
        closeLink(link)
        syncMembers()
    }

    private fun syncMembers() {
        val st = _state.value ?: return
        if (!st.group) return
        update(st.copy(members = links.values.map { CallMember(it.peerId, it.phase, it.video) }))
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
     * Ends call [id]: frees the camera, microphone and connections, and (for
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
        _memberVideo.value = emptyMap()
        val offered = links.values.filter { it.weOffered }.map { it.peerId }
        links.values.forEach(::closeLink)
        links.clear()
        media?.close()
        media = null
        CallService.stop(context)
        offered.forEach { repo.dropUnsentCallOffers(it) }
        update(st.copy(phase = CallPhase.Ended, endReason = reason, members = emptyList()))
        // The caller adds the call to the chat; in a group, that's whoever started it.
        if (st.outgoing) {
            val connectedAt = st.connectedAtMillis
            val log = when {
                connectedAt != null -> CallLog(st.startedAsVideo, OUTCOME_ANSWERED, ((System.currentTimeMillis() - connectedAt) / 1000).toInt(), st.group)
                outcome != null -> CallLog(st.startedAsVideo, outcome, group = st.group)
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

    private fun groupOf(st: CallState): String? = if (st.group) st.peerId else null

    private suspend fun callName(st: CallState): String =
        if (st.group) repo.groupNow(st.peerId)?.name ?: "Group call" else peerName(st.peerId)

    private suspend fun peerName(peerId: String): String = repo.person(peerId)?.displayName ?: "NovaChat call"

    companion object {
        /** Most people in one group call, counting yourself. Each phone sends its video to everyone else. */
        const val MAX_GROUP_CALL = 6

        private const val RING_TIMEOUT_MS = 45_000L
        private const val RING_GRACE_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val RECONNECT_TIMEOUT_MS = 20_000L
        private const val ENDED_SHOWN_MS = 1_800L
        private const val OFFER_FRESH_MS = 60_000L
        private const val OUTCOME_ANSWERED = "answered"
        private const val OUTCOME_MISSED = "missed"
        private const val OUTCOME_DECLINED = "declined"
        private const val OUTCOME_BUSY = "busy"
    }
}
