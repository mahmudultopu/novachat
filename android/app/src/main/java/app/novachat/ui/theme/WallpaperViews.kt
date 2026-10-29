package app.novachat.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** True while the app shows its dark colours, whatever the phone's own setting. */
@Composable
fun isAppDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Paints [wallpaper] behind the content: a gradient with a faint grid of dots. */
@Composable
fun Modifier.wallpaper(wallpaper: Wallpaper): Modifier {
    if (wallpaper.isPlain) return this.background(MaterialTheme.colorScheme.background)
    val dark = isAppDark()
    val colors = if (dark) wallpaper.dark else wallpaper.light
    val dot = if (dark) Color.White.copy(alpha = 0.05f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
    return this
        .background(Brush.verticalGradient(colors))
        .drawBehind {
            val step = 26.dp.toPx()
            val radius = 1.6.dp.toPx()
            var row = 0
            var y = step / 2
            while (y < size.height) {
                // Every other row is shifted by half a step, so the dots sit in a diamond pattern.
                var x = if (row % 2 == 0) step / 2 else step
                while (x < size.width) {
                    drawCircle(dot, radius, Offset(x, y))
                    x += step
                }
                y += step
                row++
            }
        }
}

/** A row of wallpaper swatches to pick from. [selected] is a wallpaper ID, or null for "Default". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WallpaperPicker(
    selected: String?,
    onSelect: (Wallpaper?) -> Unit,
    showDefault: Boolean = false,
    default: Wallpaper = Wallpaper.Plain,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showDefault) Swatch(default, "Default", selected == null) { onSelect(null) }
        Wallpaper.all.forEach { w -> Swatch(w, w.label, selected == w.id) { onSelect(w) } }
    }
}

@Composable
private fun Swatch(choice: Wallpaper, label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 56.dp, height = 84.dp)
                .clip(shape)
                .wallpaper(choice)
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
