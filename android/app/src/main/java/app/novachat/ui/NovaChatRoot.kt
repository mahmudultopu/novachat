package app.novachat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.novachat.ui.groups.GroupInfoScreen
import app.novachat.ui.groups.JoinGroupDialog
import app.novachat.ui.groups.NewGroupScreen
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.novachat.AppContainer
import app.novachat.NovaChatApp
import app.novachat.SessionState
import app.novachat.data.MessageRef
import app.novachat.ui.auth.AuthFlow
import app.novachat.ui.auth.ProfileSetupScreen
import app.novachat.ui.call.CallScreen
import app.novachat.ui.chat.ChatScreen
import app.novachat.ui.chat.ContactInfoScreen
import app.novachat.ui.home.HomeScreen
import app.novachat.ui.chats.NewChatScreen
import app.novachat.ui.settings.AppearanceScreen
import app.novachat.ui.settings.PrivacyScreen
import app.novachat.ui.settings.ProfileScreen
import app.novachat.ui.settings.StarredScreen
import app.novachat.ui.status.StatusViewerScreen
import app.novachat.ui.status.TextStatusScreen

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as NovaChatApp).container

@Composable
fun NovaChatRoot(
    inviteLink: String? = null,
    onInviteShown: () -> Unit = {},
    chatToOpen: String? = null,
    onChatOpened: () -> Unit = {},
    answerCall: Boolean = false,
    onAnswerHandled: () -> Unit = {},
) {
    val container = appContainer()
    val state by container.session.collectAsStateWithLifecycle()
    when (val s = state) {
        SessionState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        SessionState.SignedOut -> AuthFlow()
        is SessionState.SignedIn ->
            if (s.session.user.name.isBlank()) {
                ProfileSetupScreen(s.session)
            } else {
                Box(Modifier.fillMaxSize()) {
                    MainNav(inviteLink, onInviteShown, chatToOpen, onChatOpened)
                    // A call covers everything else until it ends.
                    val call by container.calls.state.collectAsStateWithLifecycle()
                    call?.let { CallScreen(it, answerCall, onAnswerHandled) }
                }
            }
    }
}

@Composable
private fun MainNav(inviteLink: String?, onInviteShown: () -> Unit, chatToOpen: String?, onChatOpened: () -> Unit) {
    val nav = rememberNavController()
    // Opened from a notification: go straight to that chat.
    LaunchedEffect(chatToOpen) {
        if (chatToOpen != null) {
            nav.navigate("chat/$chatToOpen") { popUpTo("home") }
            onChatOpened()
        }
    }
    // Android 13+ asks before an app may show notifications.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    // The join dialog: opened from the menu ("") or by an invite link.
    var joining by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(inviteLink) {
        if (inviteLink != null) {
            joining = inviteLink
            onInviteShown()
        }
    }
    joining?.let { link ->
        JoinGroupDialog(initialLink = link, onDismiss = { joining = null }, onJoined = { id ->
            joining = null
            nav.navigate("chat/$id")
        })
    }
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                openChat = { peerId -> nav.navigate("chat/$peerId") },
                openMessage = { peerId, ref -> nav.navigate("chat/$peerId?focus=${ref.senderId}:${ref.clientId}") },
                openProfile = { nav.navigate("profile") },
                openStarred = { nav.navigate("starred") },
                openPrivacy = { nav.navigate("privacy") },
                openAppearance = { nav.navigate("appearance") },
                openNewChat = { nav.navigate("newchat") },
                openNewGroup = { nav.navigate("newgroup") },
                openJoin = { joining = "" },
                openStatus = { authorId -> nav.navigate("status/$authorId") },
                openTextStatus = { nav.navigate("status-text") },
            )
        }
        composable("status/{authorId}", arguments = listOf(navArgument("authorId") { type = NavType.StringType })) { entry ->
            StatusViewerScreen(
                authorId = entry.arguments?.getString("authorId").orEmpty(),
                // Pops only the viewer, even if it asks to close twice.
                onClose = { nav.popBackStack("status/{authorId}", inclusive = true) },
            )
        }
        composable("status-text") {
            TextStatusScreen(onDone = { nav.popBackStack() })
        }
        composable("newgroup") {
            NewGroupScreen(onBack = { nav.popBackStack() }, onCreated = { id ->
                nav.navigate("chat/$id") { popUpTo("home") }
            })
        }
        composable("group/{groupId}", arguments = listOf(navArgument("groupId") { type = NavType.StringType })) { entry ->
            GroupInfoScreen(
                groupId = entry.arguments?.getString("groupId").orEmpty(),
                onBack = { nav.popBackStack() },
                openChat = { peerId -> nav.navigate("chat/$peerId") { popUpTo("home") } },
                onLeft = { nav.popBackStack() },
            )
        }
        composable(
            "chat/{peerId}?focus={focus}",
            arguments = listOf(
                navArgument("peerId") { type = NavType.StringType },
                navArgument("focus") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val peerId = entry.arguments?.getString("peerId").orEmpty()
            // A search result: "senderId:clientId" of the message to show.
            val focus = entry.arguments?.getString("focus")?.split(':')?.takeIf { it.size == 2 }?.let { MessageRef(it[0], it[1]) }
            ChatScreen(
                peerId = peerId,
                focus = focus,
                onBack = { nav.popBackStack() },
                openInfo = { isGroup -> nav.navigate(if (isGroup) "group/$peerId" else "contact/$peerId") },
            )
        }
        composable("contact/{peerId}", arguments = listOf(navArgument("peerId") { type = NavType.StringType })) { entry ->
            ContactInfoScreen(peerId = entry.arguments?.getString("peerId").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable("starred") {
            StarredScreen(onBack = { nav.popBackStack() }, openChat = { peerId -> nav.navigate("chat/$peerId") })
        }
        composable("newchat") {
            NewChatScreen(
                onBack = { nav.popBackStack() },
                openChat = { peerId -> nav.navigate("chat/$peerId") { popUpTo("home") } },
                openNewGroup = { nav.navigate("newgroup") { popUpTo("home") } },
                openJoin = {
                    nav.popBackStack()
                    joining = ""
                },
            )
        }
        composable("appearance") {
            AppearanceScreen(onBack = { nav.popBackStack() })
        }
        composable("privacy") {
            PrivacyScreen(onBack = { nav.popBackStack() })
        }
        composable("profile") {
            ProfileScreen(onBack = { nav.popBackStack() })
        }
    }
}
