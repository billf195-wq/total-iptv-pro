package com.totaliptv.pro.desktop.ui

/**
 * Next-episode banner metrics. The window used to be a fixed 196px with 48dp
 * buttons, which cut off the bottom hint (worse at large font scale). Text is
 * smaller, and the window height follows the measured content.
 */
internal object SeriesNextBannerLayout {
    const val LABEL_SP = 11
    const val SERIES_SP = 13
    const val META_SP = 11
    const val BUTTON_SP = 12
    const val HINT_SP = 11

    /** Ignore a slightly taller window so a 1px inset cannot shrink the banner forever. */
    const val SHRINK_SLOP_PX = 8

    fun targetWindowPx(measuredContentPx: Int, maxWindowPx: Int): Int {
        val cap = maxWindowPx.coerceAtLeast(1)
        return measuredContentPx.coerceIn(1, cap)
    }

    fun shouldResize(currentWindowPx: Int, measuredContentPx: Int, maxWindowPx: Int): Boolean {
        if (measuredContentPx <= 0) return false
        val target = targetWindowPx(measuredContentPx, maxWindowPx)
        if (target > currentWindowPx + 1) return true
        return currentWindowPx - target > SHRINK_SLOP_PX
    }
}
