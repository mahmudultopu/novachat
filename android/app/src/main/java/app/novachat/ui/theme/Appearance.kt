package app.novachat.ui.theme

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Light, dark, or whatever the phone uses. */
enum class ThemeMode(val id: String, val label: String) {
    System("system", "Same as phone"),
    Light("light", "Light"),
    Dark("dark", "Dark");

    companion object {
        fun from(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: System
    }
}

/**
 * The app's main colour. [main] and [soft] colour light mode; [pale] and [night] dark mode;
 * [deep] is text on the soft tint.
 */
data class Accent(
    val id: String,
    val label: String,
    val main: Color,
    val soft: Color,
    val deep: Color,
    val pale: Color,
    val night: Color,
) {
    companion object {
        val Violet = Accent("violet", "Nova violet", NovaViolet, Color(0xFFE6E0FF), Color(0xFF1B0A6B), NovaVioletLight, Color(0xFF4A30E0))
        val all = listOf(
            Violet,
            Accent("ocean", "Ocean", Color(0xFF1E6FD9), Color(0xFFD8E6FF), Color(0xFF062A5C), Color(0xFFA8C8FF), Color(0xFF1C5BB8)),
            Accent("lagoon", "Lagoon", Color(0xFF0E8F80), Color(0xFFCFF5F0), Color(0xFF00382F), Color(0xFF7FE3D4), Color(0xFF0B6E63)),
            Accent("ember", "Ember", Color(0xFFE0582B), Color(0xFFFFE0D3), Color(0xFF5A1A04), Color(0xFFFFB59A), Color(0xFFB4441E)),
            Accent("rose", "Rose", Color(0xFFD6336C), Color(0xFFFFD9E4), Color(0xFF5C0526), Color(0xFFFFB0C8), Color(0xFFA8264F)),
        )

        fun from(id: String?): Accent = all.firstOrNull { it.id == id } ?: Violet
    }
}

/** What's drawn behind a chat: a soft gradient, top to bottom, with a faint dot pattern. */
data class Wallpaper(val id: String, val label: String, val light: List<Color>, val dark: List<Color>) {
    val isPlain get() = light.isEmpty()

    companion object {
        val Plain = Wallpaper("plain", "None", emptyList(), emptyList())
        val all = listOf(
            Plain,
            Wallpaper("lilac", "Lilac", listOf(Color(0xFFEDE6FF), Color(0xFFFFFFFF)), listOf(Color(0xFF1E1838), Color(0xFF0F0B26))),
            Wallpaper("dawn", "Dawn", listOf(Color(0xFFFFE9D6), Color(0xFFF3D9FF)), listOf(Color(0xFF2A1A3A), Color(0xFF1A1030))),
            Wallpaper("lagoon", "Lagoon", listOf(Color(0xFFD4F5F0), Color(0xFFDCE8FF)), listOf(Color(0xFF0D2A2F), Color(0xFF101A33))),
            Wallpaper("sand", "Sand", listOf(Color(0xFFF7EEDC), Color(0xFFEFE3C8)), listOf(Color(0xFF2B2418), Color(0xFF1C170F))),
            Wallpaper("aurora", "Aurora", listOf(Color(0xFFC9D3F5), Color(0xFFE9D7F7)), listOf(Color(0xFF0B1026), Color(0xFF1D0F2E))),
            Wallpaper("slate", "Slate", listOf(Color(0xFFDDE3EA), Color(0xFFDDE3EA)), listOf(Color(0xFF1C232B), Color(0xFF1C232B))),
            Wallpaper("blush", "Blush", listOf(Color(0xFFFBE3EA), Color(0xFFFBE3EA)), listOf(Color(0xFF2E1820), Color(0xFF2E1820))),
        )

        fun from(id: String?): Wallpaper = all.firstOrNull { it.id == id } ?: Plain

        /** A chat's own choice wins; without one it uses the default from settings. */
        fun forChat(chatChoice: String?, default: Wallpaper): Wallpaper =
            if (chatChoice == null) default else from(chatChoice)
    }
}

/** Theme and wallpaper choices, kept on this phone only. */
class AppearanceStore(private val prefs: SharedPreferences) {
    private val _mode = MutableStateFlow(ThemeMode.from(prefs.getString(KEY_MODE, null)))
    val mode: StateFlow<ThemeMode> = _mode

    private val _accent = MutableStateFlow(Accent.from(prefs.getString(KEY_ACCENT, null)))
    val accent: StateFlow<Accent> = _accent

    private val _wallpaper = MutableStateFlow(Wallpaper.from(prefs.getString(KEY_WALLPAPER, null)))
    /** The wallpaper for chats that haven't picked their own. */
    val wallpaper: StateFlow<Wallpaper> = _wallpaper

    // Chat ID to wallpaper ID, for chats with their own wallpaper.
    private val _chatWallpapers = MutableStateFlow(
        prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(PREFIX_CHAT) && value is String) key.removePrefix(PREFIX_CHAT) to value else null
        }.toMap(),
    )

    fun setMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_MODE, mode.id).apply()
        _mode.value = mode
    }

    fun setAccent(accent: Accent) {
        prefs.edit().putString(KEY_ACCENT, accent.id).apply()
        _accent.value = accent
    }

    fun setWallpaper(wallpaper: Wallpaper) {
        prefs.edit().putString(KEY_WALLPAPER, wallpaper.id).apply()
        _wallpaper.value = wallpaper
    }

    /** The wallpaper ID a chat picked, or null when it uses the default. */
    fun chatChoice(chatId: String): Flow<String?> = _chatWallpapers.map { it[chatId] }

    fun wallpaperFor(chatId: String): Flow<Wallpaper> =
        combine(_chatWallpapers, _wallpaper) { all, default -> Wallpaper.forChat(all[chatId], default) }

    /** Give a chat its own wallpaper, or null to use the default again. */
    fun setChatWallpaper(chatId: String, wallpaper: Wallpaper?) {
        prefs.edit().apply {
            if (wallpaper == null) remove(PREFIX_CHAT + chatId) else putString(PREFIX_CHAT + chatId, wallpaper.id)
        }.apply()
        _chatWallpapers.value = if (wallpaper == null) {
            _chatWallpapers.value - chatId
        } else {
            _chatWallpapers.value + (chatId to wallpaper.id)
        }
    }

    private companion object {
        const val KEY_MODE = "theme_mode"
        const val KEY_ACCENT = "theme_accent"
        const val KEY_WALLPAPER = "wallpaper"
        const val PREFIX_CHAT = "wallpaper:"
    }
}
