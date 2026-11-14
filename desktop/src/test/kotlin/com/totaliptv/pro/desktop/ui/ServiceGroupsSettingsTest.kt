package com.totaliptv.pro.desktop.ui

import com.totaliptv.pro.desktop.AppVersion
import com.totaliptv.pro.desktop.artwork.TmdbArtwork
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceGroupsSettingsTest {
    @Test
    fun settingsAndAboutIncludeTrademarkLine() {
        val settings = File("src/main/kotlin/com/totaliptv/pro/desktop/ui/SettingsScreen.kt").readText()
        val line = "Netflix, Prime Video, HBO Max, Apple TV+, Hulu, Disney+ and Paramount+ are trademarks of their owners; not affiliated or endorsed"
        assertTrue(settings.contains(line))
        var n = 0
        var from = 0
        while (true) {
            val at = settings.indexOf(line, from)
            if (at < 0) break
            n += 1
            from = at + line.length
        }
        assertEquals(2, n)
        assertTrue(settings.contains("TmdbAttribution.TMDB"))
        assertTrue(settings.contains("TmdbAttribution.JUSTWATCH"))
        assertTrue(settings.contains("Using the built-in key"))
    }

    @Test
    fun userEnteredKeyOverridesBuiltIn() {
        assertEquals("user-key", TmdbArtwork.resolveApiKey(pref = "user-key", env = "", fileText = null, builtIn = "built-in"))
        assertEquals("built-in", TmdbArtwork.resolveApiKey(pref = "", env = "", fileText = null, builtIn = "built-in"))
        assertEquals("env-key", TmdbArtwork.resolveApiKey(pref = "", env = "env-key", fileText = null, builtIn = "built-in"))
    }

    @Test
    fun versionIs135() {
        assertEquals("1.2.37", AppVersion.VERSION_NAME)
        assertEquals(49, AppVersion.VERSION_CODE)
    }
}
