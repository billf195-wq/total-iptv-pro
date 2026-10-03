package com.totaliptv.pro.desktop.data

/**
 * How much of the TV guide is on screen.
 *
 * The old timeline was a fixed ~3 hours (`now` to `now + 3h`) at about 2.2dp
 * per minute, so a 1920 or 3440 screen showed a short strip and empty space.
 * Listings were also capped at 12 programs, which is only a few hours of
 * half-hour shows. The span below fills the measured timeline width and
 * keeps 12–24 hours ahead for sideways scrolling.
 */
object GuideSpan {
    const val PAST_MS: Long = 30L * 60_000L
    const val MIN_AHEAD_MS: Long = 12L * 3_600_000L
    const val MAX_AHEAD_MS: Long = 24L * 3_600_000L
    /** Same scale as the previous current-guide strip, so half-hour blocks stay readable. */
    const val DP_PER_MIN: Float = 2.2f
    /** About 12 hours of half-hour shows. */
    const val INITIAL_LISTING_LIMIT: Int = 24
    /** About 24 hours of half-hour shows. */
    const val EXTENDED_LISTING_LIMIT: Int = 48
    /**
     * Extra hour past a strip that already fills the width, so scrolling
     * right can grow a wide screen out to a full day. Screens narrower than
     * 12 hours already overflow via [MIN_AHEAD_MS] and do not add this.
     */
    const val SCROLL_SLACK_MS: Long = 3_600_000L
    const val OLD_AHEAD_MS: Long = 3L * 3_600_000L

    data class Timeline(
        val startMs: Long,
        val endMs: Long,
        val dpPerMin: Float,
        val widthDp: Float,
        val aheadMs: Long
    )

    /**
     * [timelineViewportDp] is the width available for the hour strip, in dp.
     * Text scaling does not change dp, so a 1.25 font scale still gets every
     * hour that fits. [extended] grows a short screen out to 24 hours after
     * the user scrolls right.
     */
    fun timeline(nowMs: Long, timelineViewportDp: Float, extended: Boolean): Timeline {
        val viewport = timelineViewportDp.coerceAtLeast(320f)
        val pastMin = PAST_MS / 60_000f
        val minutesThatFit = viewport / DP_PER_MIN
        val aheadFitMs = ((minutesThatFit - pastMin).coerceAtLeast(0f) * 60_000f).toLong()
        val fitted = aheadFitMs.coerceIn(MIN_AHEAD_MS, MAX_AHEAD_MS)
        val aheadMs = when {
            extended -> MAX_AHEAD_MS
            // Narrower than 12h: the minimum is already wider than the screen.
            aheadFitMs < MIN_AHEAD_MS -> fitted
            else -> (fitted + SCROLL_SLACK_MS).coerceAtMost(MAX_AHEAD_MS)
        }
        val start = nowMs - PAST_MS
        val end = nowMs + aheadMs
        val widthDp = ((end - start) / 60_000f) * DP_PER_MIN
        return Timeline(start, end, DP_PER_MIN, widthDp.coerceAtLeast(viewport), aheadMs)
    }

    /**
     * Program count for [get_short_epg]. A 12-hour strip starts at 24 listings.
     * Wider strips ask for about two listings per hour (half-hour shows), and
     * a scrolled or full-day strip asks for 48. Callers refetch when this grows.
     */
    fun listingLimit(aheadMs: Long, extended: Boolean): Int {
        if (extended || aheadMs >= MAX_AHEAD_MS) return EXTENDED_LISTING_LIMIT
        if (aheadMs <= MIN_AHEAD_MS) return INITIAL_LISTING_LIMIT
        val slots = kotlin.math.ceil(aheadMs / 1_800_000.0).toInt() + 2
        return slots.coerceIn(INITIAL_LISTING_LIMIT, EXTENDED_LISTING_LIMIT)
    }

    fun shouldExtend(scrollPx: Int, maxScrollPx: Int): Boolean =
        maxScrollPx > 0 && scrollPx >= maxScrollPx * 0.45f

    fun epgReaches(programs: List<EpgProgram>, untilMs: Long): Boolean {
        val end = programs.maxOfOrNull { it.endMs } ?: return false
        return end >= untilMs
    }

    fun preferLonger(primary: List<EpgProgram>, extra: List<EpgProgram>?): List<EpgProgram> {
        if (extra.isNullOrEmpty()) return primary
        val primaryEnd = primary.maxOfOrNull { it.endMs } ?: Long.MIN_VALUE
        val extraEnd = extra.maxOfOrNull { it.endMs } ?: Long.MIN_VALUE
        return if (extraEnd > primaryEnd) extra else primary
    }

    /** Keep programs that overlap the 24h guide horizon. */
    fun withinHorizon(programs: List<EpgProgram>, nowMs: Long): List<EpgProgram> {
        val start = nowMs - PAST_MS
        val end = nowMs + MAX_AHEAD_MS
        val kept = programs.filter { it.endMs > start && it.startMs < end }
        return kept.ifEmpty { programs.take(EXTENDED_LISTING_LIMIT) }
    }
}
