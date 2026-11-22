package com.totaliptv.pro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * TV-only overscan inset. 5% per edge matches the usual TV safe margin
 * (about 48 dp left/right and 27 dp top/bottom on a 1080p xhdpi screen).
 * Playback in the player stays full screen; this is for menus only.
 */
object ScreenFit {
    const val DEFAULT_PERCENT = 5
    const val MIN_PERCENT = 0
    const val MAX_PERCENT = 10

    fun normalize(raw: Int?): Int =
        raw?.coerceIn(MIN_PERCENT, MAX_PERCENT) ?: DEFAULT_PERCENT

    /** Move by [delta] percent, staying inside 0-10. Used by Lower/Raise and tests. */
    fun stepped(current: Int, delta: Int): Int =
        normalize(normalize(current) + delta)

    /** Padding on one edge, in dp, for a screen edge measured in dp. */
    fun paddingDp(screenDp: Int, percent: Int): Float {
        val p = normalize(percent)
        if (screenDp <= 0 || p <= 0) return 0f
        return screenDp * (p / 100f)
    }
}

/** Black letterbox plus the Screen-fit inset. TV menus only; do not wrap the player. */
@Composable
fun TvScreenFitFrame(percent: Int, content: @Composable () -> Unit) {
    val cfg = LocalConfiguration.current
    val h = ScreenFit.paddingDp(cfg.screenWidthDp, percent).dp
    val v = ScreenFit.paddingDp(cfg.screenHeightDp, percent).dp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = h, vertical = v)) {
            content()
        }
    }
}
