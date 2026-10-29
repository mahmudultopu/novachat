package app.novachat.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.ui.appContainer
import app.novachat.ui.common.Avatar
import app.novachat.ui.common.formatLastSeen

/** Someone's profile: big photo, name, number, about, and encryption check. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactInfoScreen(peerId: String, onBack: () -> Unit) {
    val repo = appContainer().chats
    val peer by remember(peerId) { repo.user(peerId) }.collectAsStateWithLifecycle(initialValue = null)
    val presence by remember(peerId) { repo.presence(peerId) }.collectAsStateWithLifecycle(initialValue = null)
    var showSafety by remember { mutableStateOf(false) }
    LaunchedEffect(peerId) { repo.refreshProfile(peerId) }

    if (showSafety) {
        SafetyNumberDialog(peerName = peer?.displayName ?: "them", load = { repo.safetyNumber(peerId) }, onDismiss = { showSafety = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contact info") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val name = peer?.displayName ?: "…"
            Spacer(Modifier.height(8.dp))
            Avatar(name, peerId, 140.dp, peer?.avatarId)
            Spacer(Modifier.height(16.dp))
            Text(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (peer?.name?.isNotBlank() == true) {
                Text(peer?.phone.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            formatLastSeen(presence?.online == true, presence?.lastSeenAt ?: peer?.lastSeenAt)?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(24.dp))
            InfoCard {
                Text("About", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(peer?.about?.ifBlank { null } ?: "Hey there, I'm on NovaChat.")
            }
            Spacer(Modifier.height(12.dp))
            DisappearingCard(peerId, enabled = true)
            Spacer(Modifier.height(12.dp))
            ChatWallpaperCard(peerId)
            Spacer(Modifier.height(12.dp))
            InfoCard(onClick = { showSafety = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Encryption", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Messages and calls are end-to-end encrypted. Tap to verify.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            BlockAndReport(peerId, name)
        }
    }
}

@Composable
internal fun InfoCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}
