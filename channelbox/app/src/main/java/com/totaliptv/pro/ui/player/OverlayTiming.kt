package com.totaliptv.pro.ui.player

/**
 * How long player banners stay up. Automatic banners (movie start, EPG line,
 * ready) are short; a banner the user called up with a key stays a bit longer.
 */
object PlayerOverlayTiming {
    /** Title/info banner shown on its own when playback starts. */
    const val AUTO_MS = 2_500L
    /** Banner after a remote key press. */
    const val KEY_MS = 4_500L
    /** A key within this window counts as "user asked for the banner". */
    const val KEY_WINDOW_MS = 1_500L
    /** Split screen instruction bar after switching screens. */
    const val SPLIT_CONTROLS_MS = 3_000L

    fun holdMs(nowMs: Long, lastKeyAtMs: Long): Long =
        if (lastKeyAtMs > 0L && nowMs - lastKeyAtMs in 0 until KEY_WINDOW_MS) KEY_MS else AUTO_MS
}
