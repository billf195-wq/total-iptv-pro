package com.totaliptv.pro.ui.player

/** Banner lead time before a series episode ends. The end dialog stays separate. */
object UpNextTiming {
    const val LEAD_MS = 30_000L

    fun shouldShow(seriesEpisode: Boolean, positionMs: Long, durationMs: Long): Boolean {
        if (!seriesEpisode) return false
        if (durationMs <= 0L || positionMs < 0L) return false
        val remaining = durationMs - positionMs
        return remaining in 1..LEAD_MS
    }
}
