package com.totaliptv.pro.desktop

/**
 * Local app version — must match build.gradle.kts version / packageVersion
 * and the shelf version.json shipped by publish-update.sh.
 * 1.2.0: next-episode playlist, series resume highlight, 15s logo splash.
 * 1.2.1: Windows VLC multi-URL argv (did not fix Next on Bigboybill).
 * 1.2.2: Windows kills leftover VLC and plays one episode URL; app sequential-next.
 */
object AppVersion {
    const val VERSION_CODE: Int = 14
    const val VERSION_NAME: String = "1.2.2"
}
