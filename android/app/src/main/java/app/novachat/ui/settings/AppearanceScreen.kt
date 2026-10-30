package app.novachat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.ui.appContainer
import app.novachat.ui.theme.Accent
import app.novachat.ui.theme.LocalBubbleColors
import app.novachat.ui.theme.ThemeMode
import app.novachat.ui.theme.WallpaperPicker
import app.novachat.ui.theme.isAppDark
import app.novachat.ui.theme.wallpaper

/** Light or dark, the app's colour, and the default chat wallpaper. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val appearance = appContainer().appearance
    val mode by appearance.mode.collectAsStateWithLifecycle()
    val accent by appearance.accent.collectAsStateWithLifecycle()
    val current by appearance.wallpaper.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Theme and wallpaper") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Section("Theme") {
                ThemeMode.entries.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = m == mode, onClick = { appearance.setMode(m) })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = m == mode, onClick = null)
                        Text(m.label, Modifier.padding(start = 12.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Section("Colour") {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Accent.all.forEach { a ->
                        val dark = isAppDark()
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (dark) a.pale else a.main)
                                .border(
                                    if (a == accent) 3.dp else 0.dp,
                                    if (a == accent) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    CircleShape,
                                )
                                .clickable(onClickLabel = a.label) { appearance.setAccent(a) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (a == accent) Icon(Icons.Filled.Check, a.label, tint = if (dark) a.deep else Color.White)
                        }
                    }
                }
                Text(
                    accent.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Section("Chat wallpaper") {
                Preview(current)
                Spacer(Modifier.height(12.dp))
                WallpaperPicker(selected = current.id, onSelect = { it?.let(appearance::setWallpaper) })
                Text(
                    "Each chat can also have its own wallpaper. Open the chat's info to pick one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** Two sample bubbles over the wallpaper, so the choice can be seen before opening a chat. */
@Composable
private fun Preview(sample: app.novachat.ui.theme.Wallpaper) {
    val bubbles = LocalBubbleColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(16.dp))
            .wallpaper(sample)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Hey! Did you see the new colours?",
            color = bubbles.onTheirs,
            modifier = Modifier
                .widthIn(max = 220.dp)
                .background(bubbles.theirs, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Text(
            "Yes, they look great",
            color = bubbles.onMine,
            modifier = Modifier
                .align(Alignment.End)
                .background(bubbles.mine, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
