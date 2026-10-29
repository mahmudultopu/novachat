package app.novachat.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppearanceTest {
    @Test
    fun unknownOrMissingChoicesFallBackToDefaults() {
        assertEquals(ThemeMode.System, ThemeMode.from(null))
        assertEquals(ThemeMode.System, ThemeMode.from("sepia"))
        assertEquals(ThemeMode.Dark, ThemeMode.from("dark"))
        assertEquals(Accent.Violet, Accent.from(null))
        assertEquals("rose", Accent.from("rose").id)
        assertEquals(Wallpaper.Plain, Wallpaper.from("gone"))
        assertEquals("dawn", Wallpaper.from("dawn").id)
    }

    @Test
    fun aChatsOwnWallpaperWinsOverTheDefault() {
        val default = Wallpaper.from("sand")
        assertEquals(default, Wallpaper.forChat(null, default))
        assertEquals("lilac", Wallpaper.forChat("lilac", default).id)
        // A chat can pick "None" even when the default has a wallpaper.
        assertTrue(Wallpaper.forChat("plain", default).isPlain)
    }

    @Test
    fun choicesHaveUniqueIds() {
        assertEquals(Accent.all.size, Accent.all.map { it.id }.toSet().size)
        assertEquals(Wallpaper.all.size, Wallpaper.all.map { it.id }.toSet().size)
        assertTrue(Wallpaper.all.filterNot { it.isPlain }.all { it.light.isNotEmpty() && it.dark.isNotEmpty() })
    }
}
