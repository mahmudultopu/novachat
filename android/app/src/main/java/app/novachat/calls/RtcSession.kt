package app.novachat.calls

import android.content.Context
import app.novachat.data.IceServerDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One WebRTC connection: our microphone and camera, and the other phone's
 * audio and video. Media is encrypted end to end by WebRTC itself (DTLS-SRTP);
 * the fingerprint that pins those keys travels inside the offer and answer,
 * which go through our end-to-end encrypted messages.
 *
 * Candidates are gathered before the offer or answer is sent (no trickle
 * ICE), so a call needs exactly one message each way.
 */
internal class RtcSession(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    private val egl: EglBase,
    iceServers: List<IceServerDto>,
    private val onConnection: (PeerConnection.PeerConnectionState) -> Unit,
    private val onRemoteVideo: (VideoTrack) -> Unit,
) {
    private val gathered = CompletableDeferred<Unit>()

    private val observer = object : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = onConnection(state)

        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.let(onRemoteVideo)
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceCandidate(candidate: IceCandidate) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    private val pc: PeerConnection = factory.createPeerConnection(
        PeerConnection.RTCConfiguration(
            iceServers.map { s ->
                PeerConnection.IceServer.builder(s.urls).apply {
                    s.username?.let { setUsername(it) }
                    s.credential?.let { setPassword(it) }
                }.createIceServer()
            },
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        },
        observer,
    ) ?: throw IllegalStateException("Couldn't start WebRTC")

    private val audioSource = factory.createAudioSource(MediaConstraints())
    private val audioTrack = factory.createAudioTrack("audio0", audioSource)
    private val videoSource = factory.createVideoSource(false)

    /** Our camera. Always part of the call, so turning it on needs no renegotiation. */
    val videoTrack: VideoTrack = factory.createVideoTrack("video0", videoSource).apply { setEnabled(false) }

    private var capturer: CameraVideoCapturer? = null
    private var textures: SurfaceTextureHelper? = null
    var frontCamera = true
        private set

    private fun addTracks() {
        pc.addTrack(audioTrack, listOf("nova"))
        pc.addTrack(videoTrack, listOf("nova"))
    }

    /** Makes the offer, with every candidate we could find, as SDP text. */
    suspend fun createOffer(): String {
        addTracks()
        setLocal(createSdp(offer = true))
        return gatheredDescription()
    }

    /** Takes the caller's offer and returns our answer. */
    suspend fun answer(offer: String): String {
        setRemote(SessionDescription(SessionDescription.Type.OFFER, offer))
        addTracks()
        setLocal(createSdp(offer = false))
        return gatheredDescription()
    }

    suspend fun applyAnswer(answer: String) = setRemote(SessionDescription(SessionDescription.Type.ANSWER, answer))

    fun setMuted(muted: Boolean) {
        audioTrack.setEnabled(!muted)
    }

    /** Starts or stops the camera. Returns false when there is no camera. */
    fun setCamera(on: Boolean): Boolean {
        if (on && !startCamera()) return false
        if (!on) stopCamera()
        videoTrack.setEnabled(on)
        return true
    }

    fun switchCamera(done: (front: Boolean) -> Unit) {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                frontCamera = isFront
                done(isFront)
            }

            override fun onCameraSwitchError(error: String?) {}
        })
    }

    fun close() {
        stopCamera()
        // Disposing the connection also disposes the tracks added to it.
        pc.dispose()
        videoSource.dispose()
        audioSource.dispose()
    }

    private fun startCamera(): Boolean {
        if (capturer != null) return true
        val cameras = Camera2Enumerator(context)
        val names = cameras.deviceNames
        val name = names.firstOrNull { cameras.isFrontFacing(it) == frontCamera } ?: names.firstOrNull() ?: return false
        val helper = SurfaceTextureHelper.create("camera", egl.eglBaseContext)
        val cap = cameras.createCapturer(name, null)
        cap.initialize(helper, context, videoSource.capturerObserver)
        cap.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
        frontCamera = cameras.isFrontFacing(name)
        capturer = cap
        textures = helper
        return true
    }

    private fun stopCamera() {
        capturer?.let {
            try {
                it.stopCapture()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            it.dispose()
        }
        textures?.dispose()
        capturer = null
        textures = null
    }

    private suspend fun gatheredDescription(): String {
        withTimeoutOrNull(GATHER_TIMEOUT_MS) { gathered.await() }
        return pc.localDescription?.description ?: throw IllegalStateException("No local description")
    }

    private suspend fun createSdp(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                if (cont.isActive) cont.resume(sdp)
            }

            override fun onCreateFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "createSdp failed"))
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }
        if (offer) pc.createOffer(obs, MediaConstraints()) else pc.createAnswer(obs, MediaConstraints())
    }

    private suspend fun setLocal(sdp: SessionDescription) = set { pc.setLocalDescription(it, sdp) }

    private suspend fun setRemote(sdp: SessionDescription) = set { pc.setRemoteDescription(it, sdp) }

    private suspend fun set(call: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
        call(object : SdpObserver {
            override fun onSetSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onSetFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "setDescription failed"))
            }

            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String?) {}
        })
    }

    private companion object {
        const val GATHER_TIMEOUT_MS = 4_000L
        const val CAPTURE_WIDTH = 1280
        const val CAPTURE_HEIGHT = 720
        const val CAPTURE_FPS = 30
    }
}
