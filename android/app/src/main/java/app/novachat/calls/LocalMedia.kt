package app.novachat.calls

import android.content.Context
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicInteger

/**
 * Our microphone and camera for one call. Every connection in the call (one,
 * or one per person in a group call) gets its own tracks from these sources,
 * because closing a connection also disposes the tracks it sends.
 */
internal class LocalMedia(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    private val egl: EglBase,
    /** Group calls send one stream per person, so they capture smaller video. */
    private val small: Boolean,
) {
    private val audioSource = factory.createAudioSource(MediaConstraints())
    private val videoSource = factory.createVideoSource(false)
    private val nextId = AtomicInteger()

    /** Our camera on our own screen. */
    val preview: VideoTrack = factory.createVideoTrack("preview", videoSource).apply { setEnabled(false) }

    private var capturer: CameraVideoCapturer? = null
    private var textures: SurfaceTextureHelper? = null

    var muted = false
        private set
    var cameraOn = false
        private set
    var frontCamera = true
        private set

    fun newAudioTrack(): AudioTrack =
        factory.createAudioTrack("audio" + nextId.incrementAndGet(), audioSource).apply { setEnabled(!muted) }

    fun newVideoTrack(): VideoTrack =
        factory.createVideoTrack("video" + nextId.incrementAndGet(), videoSource).apply { setEnabled(cameraOn) }

    fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    /** Starts or stops the camera. Returns false when there is no camera. */
    fun setCamera(on: Boolean): Boolean {
        if (on && !startCamera()) return false
        if (!on) stopCamera()
        cameraOn = on
        preview.setEnabled(on)
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

    /** Call after every connection using these sources is closed. */
    fun close() {
        stopCamera()
        preview.dispose()
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
        if (small) cap.startCapture(640, 360, 24) else cap.startCapture(1280, 720, 30)
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
}
