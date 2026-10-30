package app.novachat.ui.home

import app.novachat.data.MessageRef
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import app.novachat.R
import app.novachat.ui.call.CallsScreen
import app.novachat.ui.chats.ChatsScreen
import app.novachat.ui.settings.SettingsScreen
import app.novachat.ui.status.StatusScreen

private data class Tab(val label: String, val icon: @Composable () -> Painter)

private val tabs = listOf(
    Tab("Chats") { painterResource(R.drawable.ic_chats) },
    Tab("Status") { painterResource(R.drawable.ic_status) },
    Tab("Calls") { rememberVectorPainter(Icons.Filled.Call) },
    Tab("Settings") { rememberVectorPainter(Icons.Filled.Settings) },
)

/** NovaChat's home: bottom navigation instead of top tabs. */
@Composable
fun HomeScreen(
    openChat: (String) -> Unit,
    openMessage: (String, MessageRef) -> Unit,
    openProfile: () -> Unit,
    openStarred: () -> Unit,
    openPrivacy: () -> Unit,
    openAppearance: () -> Unit,
    openBackup: () -> Unit,
    openNewChat: () -> Unit,
    openNewGroup: () -> Unit,
    openJoin: () -> Unit,
    openStatus: (String) -> Unit,
    openTextStatus: () -> Unit,
) {
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
            0 -> ChatsScreen(openChat = openChat, openMessage = openMessage, openNewChat = openNewChat, openNewGroup = openNewGroup, openJoin = openJoin, modifier = modifier)
            1 -> StatusScreen(openViewer = openStatus, openTextComposer = openTextStatus, modifier = modifier)
            2 -> CallsScreen(openChat = openChat, modifier = modifier)
            else -> SettingsScreen(openProfile = openProfile, openStarred = openStarred, openPrivacy = openPrivacy, openAppearance = openAppearance, openBackup = openBackup, modifier = modifier)
        }
    }
}
