package com.totaliptv.pro.desktop

/**
 * Local app version — must match build.gradle.kts version / packageVersion
 * and the shelf version.json shipped by publish-update.sh.
 * 1.2.0: next-episode playlist, series resume highlight, 15s logo splash.
 * 1.2.1: Windows VLC multi-URL argv (did not fix Next on Bigboybill).
 * 1.2.2: Windows kills leftover VLC and plays one episode URL; app sequential-next.
 * 1.2.3: In-app Next SxEx shown whenever a following episode exists (not only after resume).
 * 1.2.4: Windows fullscreen Next via Ctrl+Right / Media Next + always-on-top control.
 * 1.2.5: Last-episode UX — overlay/hotkey say "Last episode of this series" instead of looking broken.
 * 1.2.6: Real quit (no overlay restart loop), unfreeze leaving VLC fullscreen, splash version.
 * 1.2.7: Overlay is not a Compose application Window; quit disposes it then halt-exits.
 * 1.2.8: Version in window title + splash chip; Linux series uses one URL and auto-advances.
 */
object AppVersion {
    const val VERSION_CODE: Int = 20
    const val VERSION_NAME: String = "1.2.8"
}
