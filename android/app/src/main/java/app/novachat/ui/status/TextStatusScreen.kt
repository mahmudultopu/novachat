package app.novachat.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.novachat.ui.appContainer

/** Backgrounds for text statuses. */
val StatusColors = listOf(
    Color(0xFF5B3DF5), Color(0xFF19A99B), Color(0xFFE2577B), Color(0xFFF29B38),
    Color(0xFF3C8DEB), Color(0xFF9A4DD8), Color(0xFF2FA35A), Color(0xFF1B1530),
)

/** Write a text status on a coloured background; tap the dot for another colour. */
@Composable
fun TextStatusScreen(onDone: () -> Unit) {
    val repo = appContainer().chats
    var text by rememberSaveable { mutableStateOf("") }
    var colorIndex by rememberSaveable { mutableIntStateOf(0) }
    val color = StatusColors[colorIndex]
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(Modifier.fillMaxSize().background(color).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.Filled.Close, "Cancel", tint = Color.White) }
            Box(Modifier.weight(1f))
            IconButton(onClick = { colorIndex = (colorIndex + 1) % StatusColors.size }) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(StatusColors[(colorIndex + 1) % StatusColors.size]),
                )
            }
        }
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(700) },
            textStyle = TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(32.dp).focusRequester(focus),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (text.isEmpty()) {
                        Text("Type a status", color = Color.White.copy(alpha = 0.6f), fontSize = 30.sp, textAlign = TextAlign.Center)
                    }
                    inner()
                }
            },
        )
        if (text.isNotBlank()) {
            FloatingActionButton(
                onClick = {
                    repo.postTextStatus(text, color.toArgb())
                    onDone()
                },
                containerColor = Color.White,
                contentColor = color,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Post") }
        }
    }
}
