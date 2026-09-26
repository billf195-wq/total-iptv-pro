package com.totaliptv.pro.ui.player

import android.content.Context
import android.widget.ScrollView

/**
 * Next-episode / up-next chrome shared by the TV and phone players.
 *
 * Text stays small, and every container is wrap-content (capped only by the
 * safe area) so a large font scale, a short phone, or TV overscan cannot clip
 * the bottom of a label.
 */
internal object NextEpisodeChrome {
    /** Smaller than the platform button default (~14sp). */
    const val BUTTON_TEXT_SP = 12f
    const val DIALOG_TITLE_SP = 16f
    const val DIALOG_MESSAGE_SP = 13f

    /** Extra inset so Shield overscan does not eat the banner. */
    const val OVERSCAN_FRACTION = 0.05f

    fun overscanPx(screenPx: Int): Int {
        if (screenPx <= 0) return 0
        return (screenPx * OVERSCAN_FRACTION).toInt().coerceAtLeast(0)
    }

    /** Host height cap: the screen minus top and bottom overscan. */
    fun overlayMaxHeightPx(screenHeightPx: Int, overscanPx: Int): Int =
        (screenHeightPx - overscanPx * 2).coerceAtLeast(1)

    /**
     * Message region keeps the title and the buttons on screen. The message
     * itself scrolls inside this height instead of being clipped.
     */
    fun messageMaxHeightPx(screenHeightPx: Int, overscanPx: Int, scaledDensity: Float): Int {
        val safe = overlayMaxHeightPx(screenHeightPx, overscanPx)
        val density = scaledDensity.coerceAtLeast(0.5f)
        val titlePx = (DIALOG_TITLE_SP * 2.4f * density).toInt()
        val buttonsPx = (BUTTON_TEXT_SP * 3.4f * density).toInt()
        val reserved = titlePx + buttonsPx
        val minMessage = (32f * density).toInt().coerceAtLeast(1)
        return (safe - reserved).coerceAtLeast(minMessage)
    }

    /** Vertical padding in px. [scaledDensity] already includes font scale. */
    fun verticalPaddingPx(scaledDensity: Float): Int =
        (8f * scaledDensity.coerceAtLeast(0.5f)).toInt().coerceAtLeast(1)

    fun horizontalPaddingPx(density: Float): Int =
        (10f * density.coerceAtLeast(0.5f)).toInt().coerceAtLeast(1)
}

/**
 * Vertical scroller whose height follows its child and never exceeds the
 * overscan-safe area. A fixed-height banner was clipping the bottom line.
 */
internal class NextEpisodeOverlayHost(
    context: Context,
    private val maxHeightPx: Int
) : ScrollView(context) {
    init {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = maxHeightPx.coerceAtLeast(1)
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
        )
    }
}

/** Dialog message scroller. Height follows the text until the safe-area cap. */
internal class NextEpisodeMessageScroll(
    context: Context,
    private val maxHeightPx: Int
) : ScrollView(context) {
    init {
        isFillViewport = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = maxHeightPx.coerceAtLeast(1)
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
        )
    }
}
