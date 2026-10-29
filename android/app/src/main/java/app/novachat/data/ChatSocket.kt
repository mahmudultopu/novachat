package app.novachat.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.min
import kotlin.random.Random

enum class ConnectionState { Disconnected, Connecting, Connected, Unauthorized }

/**
 * One long-lived WebSocket to the gateway, reconnecting with exponential
 * backoff and jitter whenever it drops.
 */
class ChatSocket(private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    private val _frames = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 256)
    val frames: SharedFlow<ServerFrame> = _frames

    private var socket: WebSocket? = null
    private var target: Pair<String, String>? = null // server URL, token
    private var reconnectJob: Job? = null
    private var attempt = 0

    @Synchronized
    fun connect(serverUrl: String, token: String) {
        if (target == serverUrl to token && socket != null) return
        disconnect()
        target = serverUrl to token
        open()
    }

    @Synchronized
    fun disconnect() {
        target = null
        reconnectJob?.cancel()
        socket?.close(1000, null)
        socket = null
        attempt = 0
        _state.value = ConnectionState.Disconnected
    }

    /** Sends a frame. Returns false when offline; the caller retries on reconnect. */
    fun send(text: String): Boolean =
        _state.value == ConnectionState.Connected && socket?.send(text) == true

    @Synchronized
    private fun open() {
        val (serverUrl, token) = target ?: return
        _state.value = ConnectionState.Connecting
        val wsUrl = serverUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/v1/ws"
        val request = try {
            Request.Builder().url(wsUrl).header("Authorization", "Bearer $token").build()
        } catch (e: IllegalArgumentException) {
            _state.value = ConnectionState.Disconnected
            return
        }
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== socket) return
                val frame = runCatching { NovaJson.decodeFromString<ServerFrame>(text) }.getOrNull() ?: return
                if (frame.type == "ready") {
                    attempt = 0
                    _state.value = ConnectionState.Connected
                }
                _frames.tryEmit(frame)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, null)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket, response)
        })
    }

    @Synchronized
    private fun dropped(webSocket: WebSocket, response: Response?) {
        if (webSocket !== socket) return
        socket = null
        if (response?.code == 401) {
            _state.value = ConnectionState.Unauthorized
            return
        }
        _state.value = ConnectionState.Connecting
        val backoff = min(30_000L, 1_000L shl min(attempt, 5))
        attempt++
        reconnectJob = scope.launch {
            delay(backoff / 2 + Random.nextLong(backoff / 2 + 1))
            open()
        }
    }
}
