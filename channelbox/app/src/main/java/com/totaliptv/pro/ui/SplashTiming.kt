package com.totaliptv.pro.ui

/**
 * Logo banner duration shared with desktop and Pro2.
 * 15s is the logo splash; 30s is a separate catalog-hold cap.
 *
 * Keep the composable name [LogoBannerSplash] (not StartupSplash) so a leftover
 * `src/tv/.../StartupSplash.kt` from an older tree cannot clash with main.
 */
object SplashTiming {
    const val DURATION_MS: Long = 15_000L
    const val MAX_CATALOG_HOLD_MS: Long = 30_000L
}

object StartupSplashGate {
    @Volatile var shownThisProcess: Boolean = false
}
