package com.totaliptv.pro.desktop

/**
 * Local app version — must match build.gradle.kts version / packageVersion
 * and the shelf version.json shipped by publish-update.sh.
 * 1.2.0: next-episode playlist, series resume highlight, 15s logo splash.
 * 1.2.1: Windows VLC Next advances remaining episodes (Linux playlist unchanged).
 */
object AppVersion {
    const val VERSION_CODE: Int = 13
    const val VERSION_NAME: String = "1.2.1"
}
