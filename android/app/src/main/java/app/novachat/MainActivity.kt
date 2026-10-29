package app.novachat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.novachat.notify.Notifier
import app.novachat.ui.NovaChatRoot
import app.novachat.ui.theme.NovaChatTheme

class MainActivity : ComponentActivity() {
    /** An invite link the app was opened with, until it is shown. */
    private var inviteLink by mutableStateOf<String?>(null)

    /** A chat to open, from a notification. */
    private var chatToOpen by mutableStateOf<String?>(null)

    private val container get() = (application as NovaChatApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readIntent(intent)
        setContent {
            NovaChatTheme {
                NovaChatRoot(
                    inviteLink = inviteLink,
                    onInviteShown = { inviteLink = null },
                    chatToOpen = chatToOpen,
                    onChatOpened = { chatToOpen = null },
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

    private fun readIntent(intent: Intent?) {
        if (intent?.action == Notifier.ACTION_OPEN_CHAT) {
            chatToOpen = intent.getStringExtra(Notifier.EXTRA_CHAT)
            return
        }
        val uri = intent?.data ?: return
        if (uri.host == "join" || uri.path.orEmpty().startsWith("/join/")) inviteLink = uri.toString()
    }
}
