package app.novachat

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.novachat.notify.Notifier
import app.novachat.ui.NovaChatRoot
import app.novachat.ui.theme.NovaChatTheme
import app.novachat.ui.theme.isAppDark

class MainActivity : ComponentActivity() {
    /** An invite link the app was opened with, until it is shown. */
    private var inviteLink by mutableStateOf<String?>(null)

    /** A chat to open, from a notification. */
    private var chatToOpen by mutableStateOf<String?>(null)

    /** "Answer" was tapped on an incoming call notification. */
    private var answerCall by mutableStateOf(false)

    private val container get() = (application as NovaChatApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readIntent(intent)
        setContent {
            // Calls show over the lock screen; nothing else does.
            val call by container.calls.state.collectAsState()
            LaunchedEffect(call == null) { if (call == null) showOverLockScreen(false) else showOverLockScreen(true) }
            val mode by container.appearance.mode.collectAsState()
            val accent by container.appearance.accent.collectAsState()
            NovaChatTheme(mode, accent) {
                // Status bar icons follow the app's theme, which may differ from the phone's.
                val dark = isAppDark()
                LaunchedEffect(dark) {
                    val style = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    }
                    enableEdgeToEdge(style, style)
                }
                NovaChatRoot(
                    inviteLink = inviteLink,
                    onInviteShown = { inviteLink = null },
                    chatToOpen = chatToOpen,
                    onChatOpened = { chatToOpen = null },
                    answerCall = answerCall,
                    onAnswerHandled = { answerCall = false },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.setAppVisible(true)
    }

    override fun onStop() {
        container.setAppVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun showOverLockScreen(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    private fun readIntent(intent: Intent?) {
        when (intent?.action) {
            Notifier.ACTION_SHOW_CALL -> return showOverLockScreen(true)
            Notifier.ACTION_ACCEPT_CALL -> {
                showOverLockScreen(true)
                answerCall = true
                return
            }
        }
        if (intent?.action == Notifier.ACTION_OPEN_CHAT) {
            chatToOpen = intent.getStringExtra(Notifier.EXTRA_CHAT)
            return
        }
        val uri = intent?.data ?: return
        if (uri.host == "join" || uri.path.orEmpty().startsWith("/join/")) inviteLink = uri.toString()
    }
}
