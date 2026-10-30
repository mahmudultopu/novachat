@file:OptIn(ExperimentalTextApi::class)

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.novachat.R

// NovaChat's own palette: deep violet with an aqua spark, not messenger green.
val NovaViolet = Color(0xFF5B3DF5)
val NovaVioletLight = Color(0xFFB9A8FF)
val NovaAqua = Color(0xFF19C6B4)
val NovaInk = Color(0xFF0E0C1D)
val NovaMist = Color(0xFFF7F6FC)

private val Light = lightColorScheme(
    primary = NovaViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E4FF),
    onPrimaryContainer = Color(0xFF1B0A6B),
    secondary = Color(0xFF0FA897),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2F6F1),
    onSecondaryContainer = Color(0xFF00382F),
    background = NovaMist,
    onBackground = NovaInk,
    surface = Color.White,
    onSurface = NovaInk,
    surfaceVariant = Color(0xFFEFEDF6),
    onSurfaceVariant = Color(0xFF6A6680),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFAFE),
    surfaceContainer = Color(0xFFF3F1FA),
    surfaceContainerHigh = Color(0xFFEDEAF6),
    surfaceContainerHighest = Color(0xFFE6E3F1),
    outline = Color(0xFFCFCADF),
    outlineVariant = Color(0xFFE8E5F0),
)

// Dark mode is a near-black indigo, so it looks rich on OLED screens.
private val Dark = darkColorScheme(
    primary = NovaVioletLight,
    onPrimary = Color(0xFF240E8A),
    primaryContainer = Color(0xFF3F25C4),
    onPrimaryContainer = Color(0xFFE6E0FF),
    secondary = Color(0xFF4FE3D0),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF005046),
    onSecondaryContainer = Color(0xFFCFF5F0),
    background = Color(0xFF0B0A14),
    onBackground = Color(0xFFECE9F7),
    surface = Color(0xFF12101E),
    onSurface = Color(0xFFECE9F7),
    surfaceVariant = Color(0xFF242036),
    onSurfaceVariant = Color(0xFFADA8C4),
    surfaceContainerLowest = Color(0xFF08070F),
    surfaceContainerLow = Color(0xFF14121F),
    surfaceContainer = Color(0xFF1A1727),
    surfaceContainerHigh = Color(0xFF221F31),
    surfaceContainerHighest = Color(0xFF2B283B),
    outline = Color(0xFF4A4562),
    outlineVariant = Color(0xFF2A2638),
)

/** Colours for chat bubbles, which Material's scheme does not cover. */
@Immutable
data class BubbleColors(
    val mine: Color,
    val onMine: Color,
    val theirs: Color,
    val onTheirs: Color,
    val readTick: Color,
    /** A soft diagonal sheen of [mine], used for my bubbles, the send button and the new chat button. */
    val mineBrush: Brush = Brush.linearGradient(listOf(mine, mine)),
)

val LocalBubbleColors = staticCompositionLocalOf {
    BubbleColors(NovaViolet, Color.White, Color.White, NovaInk, NovaAqua)
}

/** The brand gradient, violet into aqua, for the NovaChat wordmark. */
val NovaBrandBrush = Brush.linearGradient(listOf(Color(0xFF6A4BFF), Color(0xFF8B5CF6), Color(0xFF19C6B4)))

private fun sheen(base: Color, deep: Color) =
    Brush.linearGradient(listOf(lerp(base, Color.White, 0.14f), base, lerp(base, deep, 0.22f)))

private val NovaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private fun novaFont(weight: Int) =
    Font(R.font.nova_sans, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Plus Jakarta Sans (SIL Open Font License), bundled so every phone shows the same type. */
val NovaSans = FontFamily(
    novaFont(400),
    novaFont(500),
    novaFont(600),
    novaFont(700),
    novaFont(800),
)

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
    TextStyle(fontFamily = NovaSans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

private val NovaType = Typography(
    displaySmall = style(34, 42, FontWeight.ExtraBold, -0.8),
    headlineLarge = style(30, 38, FontWeight.ExtraBold, -0.6),
    headlineMedium = style(26, 34, FontWeight.Bold, -0.5),
    headlineSmall = style(22, 30, FontWeight.Bold, -0.3),
    titleLarge = style(22, 28, FontWeight.ExtraBold, -0.4),
    titleMedium = style(16, 22, FontWeight.SemiBold, -0.1),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 23),
    bodyMedium = style(14, 20),
    bodySmall = style(12, 17),
    labelLarge = style(14, 20, FontWeight.SemiBold, 0.1),
    labelMedium = style(12, 16, FontWeight.SemiBold, 0.2),
    labelSmall = style(11, 15, FontWeight.Medium, 0.2),
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
        BubbleColors(accent.night, Color.White, Color(0xFF1F1B30), Color(0xFFECE9F7), Color(0xFF6FF0DF), sheen(accent.night, accent.deep))
    } else {
        BubbleColors(accent.main, Color.White, Color.White, NovaInk, Color(0xFF8FF7EA), sheen(accent.main, accent.deep))
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
