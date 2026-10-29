package app.novachat.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.novachat.R
import app.novachat.ui.chats.ChatsScreen
import app.novachat.ui.settings.SettingsScreen

private data class Tab(val label: String, val icon: @Composable () -> Painter)

private val tabs = listOf(
    Tab("Chats") { painterResource(R.drawable.ic_chats) },
    Tab("Status") { painterResource(R.drawable.ic_status) },
    Tab("Calls") { rememberVectorPainter(Icons.Filled.Call) },
    Tab("Settings") { rememberVectorPainter(Icons.Filled.Settings) },
)

/** NovaChat's home: bottom navigation instead of top tabs. */
@Composable
fun HomeScreen(openChat: (String) -> Unit, openProfile: () -> Unit, openStarred: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = { Icon(tab.icon(), null) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (selected) {
            0 -> ChatsScreen(openChat = openChat, modifier = modifier)
            1 -> ComingSoon("Status", "Share photo, video and text updates that disappear after 24 hours.", modifier)
            2 -> ComingSoon("Calls", "End-to-end encrypted voice and video calls are next on the roadmap.", modifier)
            else -> SettingsScreen(openProfile = openProfile, openStarred = openStarred, modifier = modifier)
        }
    }
}

@Composable
private fun ComingSoon(title: String, text: String, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(R.drawable.ic_status), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
