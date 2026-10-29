package app.novachat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// NovaChat's own palette: deep violet with an aqua spark, not messenger green.
val NovaViolet = Color(0xFF5B3DF5)
val NovaVioletLight = Color(0xFFB9A8FF)
val NovaAqua = Color(0xFF19C6B4)
val NovaInk = Color(0xFF0F0B26)
val NovaMist = Color(0xFFF6F4FF)

private val Light = lightColorScheme(
    primary = NovaViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E0FF),
    onPrimaryContainer = Color(0xFF1B0A6B),
    secondary = NovaAqua,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF5F0),
    onSecondaryContainer = Color(0xFF00382F),
    background = NovaMist,
    onBackground = NovaInk,
    surface = Color.White,
    onSurface = NovaInk,
    surfaceVariant = Color(0xFFEDEAF7),
    onSurfaceVariant = Color(0xFF5E5A73),
    surfaceContainer = Color(0xFFF1EEFB),
    outline = Color(0xFFCAC4DE),
)

private val Dark = darkColorScheme(
    primary = NovaVioletLight,
    onPrimary = Color(0xFF240E8A),
    primaryContainer = Color(0xFF3F25C4),
    onPrimaryContainer = Color(0xFFE6E0FF),
    secondary = Color(0xFF4FE3D0),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF005046),
    onSecondaryContainer = Color(0xFFCFF5F0),
    background = NovaInk,
    onBackground = Color(0xFFE7E3F7),
    surface = Color(0xFF17122F),
    onSurface = Color(0xFFE7E3F7),
    surfaceVariant = Color(0xFF2A2446),
    onSurfaceVariant = Color(0xFFB7B1CF),
    surfaceContainer = Color(0xFF1D1738),
    outline = Color(0xFF4A4466),
)

/** Colours for chat bubbles, which Material's scheme does not cover. */
@Immutable
data class BubbleColors(
    val mine: Color,
    val onMine: Color,
    val theirs: Color,
    val onTheirs: Color,
    val readTick: Color,
)

val LocalBubbleColors = staticCompositionLocalOf {
    BubbleColors(NovaViolet, Color.White, Color.White, NovaInk, NovaAqua)
}

private val NovaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp),
)

private val NovaType = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.2.sp),
)

@Composable
fun NovaChatTheme(
    mode: ThemeMode = ThemeMode.System,
    accent: Accent = Accent.Violet,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val bubbles = if (dark) {
        BubbleColors(accent.night, Color.White, Color(0xFF241D42), Color(0xFFE7E3F7), Color(0xFF4FE3D0))
    } else {
        BubbleColors(accent.main, Color.White, Color.White, NovaInk, Color(0xFF7FF5E6))
    }
    val scheme = if (dark) {
        Dark.copy(
            primary = accent.pale,
            onPrimary = accent.deep,
            primaryContainer = accent.night,
            onPrimaryContainer = accent.soft,
        )
    } else {
        Light.copy(primary = accent.main, primaryContainer = accent.soft, onPrimaryContainer = accent.deep)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalBubbleColors provides bubbles) {
        MaterialTheme(
            colorScheme = scheme,
            typography = NovaType,
            shapes = NovaShapes,
            content = content,
        )
    }
}
