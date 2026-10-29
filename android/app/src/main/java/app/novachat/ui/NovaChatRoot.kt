package app.novachat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.novachat.ui.auth.AuthFlow
import app.novachat.ui.auth.ProfileSetupScreen
import app.novachat.ui.chat.ChatScreen
import app.novachat.ui.chat.ContactInfoScreen
import app.novachat.ui.home.HomeScreen
import app.novachat.ui.settings.ProfileScreen
import app.novachat.ui.settings.StarredScreen

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as NovaChatApp).container

@Composable
fun NovaChatRoot() {
    val container = appContainer()
    val state by container.session.collectAsStateWithLifecycle()
    when (val s = state) {
        SessionState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        SessionState.SignedOut -> AuthFlow()
        is SessionState.SignedIn ->
            if (s.session.user.name.isBlank()) ProfileSetupScreen(s.session) else MainNav()
    }
}

@Composable
private fun MainNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                openChat = { peerId -> nav.navigate("chat/$peerId") },
                openProfile = { nav.navigate("profile") },
                openStarred = { nav.navigate("starred") },
            )
        }
        composable("chat/{peerId}", arguments = listOf(navArgument("peerId") { type = NavType.StringType })) { entry ->
            val peerId = entry.arguments?.getString("peerId").orEmpty()
            ChatScreen(
                peerId = peerId,
                onBack = { nav.popBackStack() },
                openInfo = { nav.navigate("contact/$peerId") },
            )
        }
        composable("contact/{peerId}", arguments = listOf(navArgument("peerId") { type = NavType.StringType })) { entry ->
            ContactInfoScreen(peerId = entry.arguments?.getString("peerId").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable("starred") {
            StarredScreen(onBack = { nav.popBackStack() }, openChat = { peerId -> nav.navigate("chat/$peerId") })
        }
        composable("profile") {
            ProfileScreen(onBack = { nav.popBackStack() })
        }
    }
}
