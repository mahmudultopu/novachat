package app.novachat.ui.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/** Records a voice note as small mono AAC (about 240 KB a minute). */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording get() = recorder != null

    fun start(target: File): Boolean {
        cancel()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(22_050)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(target.path)
            r.prepare()
            r.start()
            recorder = r
            file = target
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            r.release()
            target.delete()
            false
        }
    }

    fun elapsedMs(): Long = if (recorder != null) SystemClock.elapsedRealtime() - startedAt else 0

    /** Stops and returns the recording, or null if it was too short to keep. */
    fun stop(): Pair<File, Long>? {
        val r = recorder ?: return null
        val f = file
        val duration = elapsedMs()
        recorder = null
        file = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (!ok || f == null || duration < MIN_MS) {
            f?.delete()
            return null
        }
        return f to duration
    }

    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        r.release()
        file?.delete()
        file = null
    }

    private companion object {
        const val MIN_MS = 800L
    }
}
