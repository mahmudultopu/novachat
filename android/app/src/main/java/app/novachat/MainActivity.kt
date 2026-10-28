package app.novachat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.novachat.ui.NovaChatRoot
import app.novachat.ui.theme.NovaChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NovaChatTheme {
                NovaChatRoot()
            }
        }
    }
}
