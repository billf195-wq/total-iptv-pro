package com.totaliptv.pro.ui.player

/** Banner lead time before a series episode ends. The end dialog stays separate. */
object UpNextTiming {
    const val LEAD_MS = 30_000L

    /** Seconds the end-of-episode countdown runs before the next episode plays on its own. */
    const val AUTO_PLAY_SECONDS = 10

    /** Seconds until auto-play: time left in this episode plus the end countdown. */
    fun autoPlaySecondsLeft(positionMs: Long, durationMs: Long): Int {
        val remaining = (durationMs - positionMs).coerceAtLeast(0L)
        return ((remaining + 999L) / 1000L).toInt() + AUTO_PLAY_SECONDS
    }

    fun bannerText(label: String, autoPlay: Boolean, secondsLeft: Int): String =
        if (autoPlay) "Up next: $label  ·  plays in ${secondsLeft}s" else "Up next: $label"

    fun countdownMessage(label: String, secondsLeft: Int): String =
        "Playing next in ${secondsLeft}s\n\n$label"

    fun shouldShow(seriesEpisode: Boolean, positionMs: Long, durationMs: Long): Boolean {
        if (!seriesEpisode) return false
        if (durationMs <= 0L || positionMs < 0L) return false
        val remaining = durationMs - positionMs
        return remaining in 1..LEAD_MS
    }
}
