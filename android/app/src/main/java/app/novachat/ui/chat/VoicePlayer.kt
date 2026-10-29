package app.novachat.ui.chat

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What is playing: which file, how far in, and whether it is paused. */
data class PlaybackState(val path: String? = null, val positionMs: Int = 0, val durationMs: Int = 0, val playing: Boolean = false)

/** Plays one voice note at a time for a chat screen. */
class VoicePlayer(private val scope: CoroutineScope) {
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    fun toggle(path: String) {
        val current = _state.value
        val p = player
        when {
            current.path == path && p != null && current.playing -> {
                p.pause()
                _state.value = current.copy(playing = false)
            }
            current.path == path && p != null -> {
                p.start()
                _state.value = current.copy(playing = true)
                tick()
            }
            else -> play(path)
        }
    }

    private fun play(path: String) {
        release()
        val p = MediaPlayer()
        try {
            p.setDataSource(path)
            p.prepare()
        } catch (e: Exception) {
            p.release()
            return
        }
        p.setOnCompletionListener { release() }
        p.start()
        player = p
        _state.value = PlaybackState(path, 0, p.duration, true)
        tick()
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                if (!_state.value.playing) break
                _state.value = _state.value.copy(positionMs = p.currentPosition)
                delay(100)
            }
        }
    }

    fun release() {
        ticker?.cancel()
        player?.release()
        player = null
        _state.value = PlaybackState()
    }
}
