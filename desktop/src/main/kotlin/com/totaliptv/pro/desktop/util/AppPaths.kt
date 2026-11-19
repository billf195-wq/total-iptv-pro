package com.totaliptv.pro.desktop.util

import java.nio.file.Path

/**
 * Shared OS-aware paths so prefs / favorites / resume / updates all agree.
 * Linux: $XDG_CONFIG_HOME/total-iptv-pro (default ~/.config/total-iptv-pro)
 * Windows: %APPDATA%\total-iptv-pro (user.home\AppData\Roaming\total-iptv-pro)
 * macOS: ~/Library/Application Support/total-iptv-pro
 */
object AppPaths {
    private val osName: String = System.getProperty("os.name").orEmpty().lowercase()

    val isWindows: Boolean = osName.contains("win")
    val isLinux: Boolean = osName.contains("linux")
    val isMac: Boolean = osName.contains("mac") || osName.contains("darwin")

    /** Config dir for prefs.json, favorites.json, resume.json */
    val configDir: Path = configDirFor(
        isWindows = isWindows,
        isMac = isMac,
        home = System.getProperty("user.home").orEmpty(),
        appData = System.getenv("APPDATA"),
        xdgConfigHome = System.getenv("XDG_CONFIG_HOME")
    )

    fun prefsHintPath(): String = configDir.resolve("prefs.json").toString()
}

/** Pure so unit tests can cover Windows, macOS, and Linux without changing the host OS. */
internal fun configDirFor(
    isWindows: Boolean,
    isMac: Boolean,
    home: String,
    appData: String?,
    xdgConfigHome: String? = null
): Path = when {
    isWindows -> {
        val base = appData?.takeIf { it.isNotBlank() }
            ?: Path.of(home, "AppData", "Roaming").toString()
        Path.of(base, "total-iptv-pro")
    }
    isMac -> Path.of(home, "Library", "Application Support", "total-iptv-pro")
    else -> {
        val base = xdgConfigHome?.takeIf { it.isNotBlank() }
            ?: Path.of(home, ".config").toString()
        Path.of(base, "total-iptv-pro")
    }
}
