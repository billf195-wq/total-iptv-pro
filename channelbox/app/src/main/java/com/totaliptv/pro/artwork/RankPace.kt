package com.totaliptv.pro.artwork

/** How long Top rated waits before it re-sorts while ratings are still arriving. */
object RankPace {
    const val INTERVAL_MS = 10_000L

    /**
     * Delay before the next rank. The first rank is immediate.
     * A settled queue ranks immediately so the row catches up when visible fetches finish.
     * Otherwise the row updates at most once per [INTERVAL_MS].
     */
    fun delayMs(lastRankMs: Long, nowMs: Long, settled: Boolean): Long {
        if (lastRankMs < 0L) return 0L
        if (settled) return 0L
        val elapsed = nowMs - lastRankMs
        if (elapsed >= INTERVAL_MS) return 0L
        return INTERVAL_MS - elapsed
    }
}
