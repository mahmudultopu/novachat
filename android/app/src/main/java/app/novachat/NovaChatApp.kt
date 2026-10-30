package app.novachat

import android.app.Application
import android.content.Context
import app.novachat.calls.CallManager
import app.novachat.notify.ConnectionService
import app.novachat.notify.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import app.novachat.data.ApiClient
import app.novachat.data.ChatRepository
import app.novachat.data.ChatSocket
import app.novachat.data.E2eKeys
import app.novachat.data.MediaFiles
import app.novachat.data.Session
import app.novachat.data.SessionStore
import app.novachat.data.Vault
import app.novachat.data.local.ChatDatabase
import app.novachat.ui.theme.AppearanceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class NovaChatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Where the app is: still loading from disk, signed out, or signed in. */
sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val session: Session) : SessionState
}

/** Hand-wired dependencies. Swap for Hilt once the app grows more modules. */
class AppContainer(app: Application) {
    private companion object {
        const val KEY_BACKGROUND = "background_connection"
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val sessionStore = SessionStore(app)
    val api = ApiClient(http)
    val database = ChatDatabase.open(app)
    val chats = ChatRepository(api, ChatSocket(http, scope), database, E2eKeys(database.chats(), Vault()), MediaFiles(app), scope)

    val notifier = Notifier(app, chats.media)
    val calls = CallManager(app, chats, notifier, scope)

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val appearance = AppearanceStore(prefs)
    private val _backgroundConnection = MutableStateFlow(prefs.getBoolean(KEY_BACKGROUND, true))
    /** Stay connected while the app is closed, so messages notify right away. */
    val backgroundConnection: StateFlow<Boolean> = _backgroundConnection

    private val appContext = app

    fun setBackgroundConnection(on: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND, on).apply()
        _backgroundConnection.value = on
        val signedIn = session.value is SessionState.SignedIn
        if (on && signedIn) ConnectionService.start(appContext) else ConnectionService.stop(appContext)
    }

    /** Call from the activity: the app came on screen or left it. */
    fun setAppVisible(visible: Boolean) {
        chats.setActive(visible)
        if (visible && _backgroundConnection.value && session.value is SessionState.SignedIn) {
            ConnectionService.start(appContext)
        }
    }

    val session: StateFlow<SessionState> = sessionStore.session
        .map { if (it == null) SessionState.SignedOut else SessionState.SignedIn(it) }
        .stateIn(scope, SharingStarted.Eagerly, SessionState.Loading)

    init {
        // Until an activity says otherwise (e.g. started after a reboot), the app is in the background.
        chats.setActive(false)
        scope.launch {
            session.collect { state ->
                when (state) {
                    is SessionState.SignedIn -> {
                        chats.start(state.session)
                        if (_backgroundConnection.value) ConnectionService.start(appContext)
                    }
                    SessionState.SignedOut -> {
                        chats.stop(clearData = true)
                        ConnectionService.stop(appContext)
                        notifier.clearAll()
                    }
                    SessionState.Loading -> Unit
                }
            }
        }
        scope.launch {
            chats.incoming.collect { if (!chats.isOnScreen(it.chatId)) notifier.show(it) }
        }
        // The server no longer accepts our token (signed out elsewhere).
        scope.launch { chats.unauthorized.collect { sessionStore.signOut() } }
    }
}
