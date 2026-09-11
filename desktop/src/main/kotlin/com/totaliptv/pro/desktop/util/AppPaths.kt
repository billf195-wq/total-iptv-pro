package com.totaliptv.pro.desktop.util

import java.nio.file.Path

/**
 * Shared OS-aware paths so prefs / favorites / resume / updates all agree.
 * Linux: ~/.config/total-iptv-pro
 * Windows: %APPDATA%\total-iptv-pro (user.home\AppData\Roaming\total-iptv-pro)
 */
object AppPaths {
    private val osName: String = System.getProperty("os.name").orEmpty().lowercase()

    val isWindows: Boolean = osName.contains("win")
    val isLinux: Boolean = osName.contains("linux")
    val isMac: Boolean = osName.contains("mac") || osName.contains("darwin")

    /** Config dir for prefs.json, favorites.json, resume.json */
    val configDir: Path = when {
        isWindows -> {
            val appData = System.getenv("APPDATA")
                ?.takeIf { it.isNotBlank() }
                ?: Path.of(System.getProperty("user.home"), "AppData", "Roaming").toString()
            Path.of(appData, "total-iptv-pro")
        }
        else -> Path.of(System.getProperty("user.home"), ".config", "total-iptv-pro")
    }

    fun prefsHintPath(): String = configDir.resolve("prefs.json").toString()
}
