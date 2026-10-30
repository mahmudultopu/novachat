package app.novachat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.R
import app.novachat.ui.appContainer
import app.novachat.ui.theme.Wallpaper
import app.novachat.ui.theme.WallpaperPicker

/** Chat info card: this chat's own wallpaper, or the default one. Only this phone sees it. */
@Composable
fun ChatWallpaperCard(chatId: String) {
    val appearance = appContainer().appearance
    val choice by remember(chatId) { appearance.chatChoice(chatId) }.collectAsStateWithLifecycle(initialValue = null)
    val default by appearance.wallpaper.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.clickable { choosing = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_palette), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Wallpaper", style = MaterialTheme.typography.titleSmall)
                Text(
                    choice?.let { Wallpaper.from(it).label } ?: "Default (${default.label})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Chat wallpaper") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    WallpaperPicker(
                        selected = choice,
                        onSelect = {
                            appearance.setChatWallpaper(chatId, it)
                            choosing = false
                        },
                        showDefault = true,
                        default = default,
                    )
                    Text(
                        "Only you see this wallpaper.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } },
        )
    }
}
