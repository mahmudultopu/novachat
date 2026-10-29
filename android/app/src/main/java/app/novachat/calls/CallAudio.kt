package app.novachat.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The sounds around a call: our ringtone for incoming calls, the ringing
 * tone while the other phone rings, routing between earpiece and speaker,
 * and turning the screen off when the phone is held to the ear.
 */
internal class CallAudio(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var ringback: ToneGenerator? = null
    private val proximity: PowerManager.WakeLock? = context.getSystemService(PowerManager::class.java)
        .takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
        ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "novachat:call")
        ?.apply { setReferenceCounted(false) }

    /** Plays the phone's ringtone (and vibrates) until [stopRinging], as the ringer mode allows. */
    fun startRinging() {
        stopRinging()
        val mode = audio.ringerMode
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            vibrator = (
                if (Build.VERSION.SDK_INT >= 31) {
                    context.getSystemService(VibratorManager::class.java).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Vibrator::class.java)
                }
                ).also { it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 1_200), 0)) }
        }
    }

    fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    /** Takes over the audio for a call. */
    fun startCall(speaker: Boolean) {
        if (focus == null) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            audio.requestAudioFocus(request)
            focus = request
        }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(speaker)
    }

    fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (on && speaker != null) audio.setCommunicationDevice(speaker) else audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = on
        }
    }

    /** The "ringing" tone the caller hears while the other phone rings. */
    fun startRingback() {
        if (ringback != null) return
        ringback = try {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        } catch (e: RuntimeException) {
            null // No tone generator on this phone: ring silently.
        }
    }

    fun stopRingback() {
        ringback?.stopTone()
        ringback?.release()
        ringback = null
    }

    /** Turns the screen off near the ear, for voice calls on the earpiece. */
    fun setProximity(on: Boolean) {
        val lock = proximity ?: return
        if (on && !lock.isHeld) lock.acquire(MAX_CALL_MS) else if (!on && lock.isHeld) lock.release()
    }

    /** Gives the audio back after a call. */
    fun stopCall() {
        stopRinging()
        stopRingback()
        setProximity(false)
        setSpeaker(false)
        audio.mode = AudioManager.MODE_NORMAL
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private companion object {
        const val RINGBACK_VOLUME = 60
        const val MAX_CALL_MS = 4 * 60 * 60 * 1000L
    }
}
