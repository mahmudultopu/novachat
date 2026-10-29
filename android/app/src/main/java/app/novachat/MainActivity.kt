package app.novachat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.novachat.ui.NovaChatRoot
import app.novachat.ui.theme.NovaChatTheme

class MainActivity : ComponentActivity() {
    /** An invite link the app was opened with, until it is shown. */
    private var inviteLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readInvite(intent)
        setContent {
            NovaChatTheme {
                NovaChatRoot(inviteLink = inviteLink, onInviteShown = { inviteLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readInvite(intent)
    }

    private fun readInvite(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.host == "join" || uri.path.orEmpty().startsWith("/join/")) inviteLink = uri.toString()
    }
}
