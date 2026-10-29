package app.novachat

import android.app.Application
import app.novachat.data.ApiClient
import app.novachat.data.ChatRepository
import app.novachat.data.ChatSocket
import app.novachat.data.Session
import app.novachat.data.SessionStore
import app.novachat.data.local.ChatDatabase
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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val sessionStore = SessionStore(app)
    val api = ApiClient(http)
    val database = ChatDatabase.open(app)
    val chats = ChatRepository(api, ChatSocket(http, scope), database.chats(), scope)

    val session: StateFlow<SessionState> = sessionStore.session
        .map { if (it == null) SessionState.SignedOut else SessionState.SignedIn(it) }
        .stateIn(scope, SharingStarted.Eagerly, SessionState.Loading)

    init {
        scope.launch {
            session.collect { state ->
                when (state) {
                    is SessionState.SignedIn -> chats.start(state.session)
                    SessionState.SignedOut -> chats.stop(clearData = true)
                    SessionState.Loading -> Unit
                }
            }
        }
        // The server no longer accepts our token (signed out elsewhere).
        scope.launch { chats.unauthorized.collect { sessionStore.signOut() } }
    }
}
