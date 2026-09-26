package com.totaliptv.pro.ui.epg

import com.totaliptv.pro.data.model.EpgProgram
import kotlin.math.ceil

/**
 * How much of the guide to draw and fetch.
 *
 * The grid used to be a fixed 4-hour strip at 220dp per hour, so a 1080p
 * Shield showed only a couple of hours and horizontal scroll stopped there.
 * `get_short_epg` was also limited to 10 listings, which often ends in that
 * same short range.
 *
 * The visible span is however many hours fit the timeline. A few more hours
 * are kept past the edge for scrolling. Listings are trimmed to [MAX_HOURS]
 * so a 3 GB Shield does not retain a full-day table for every channel.
 */
object GuideWindow {
    /** Hour column width in dp. About six hours fit a 1080p Shield timeline. */
    const val DP_PER_HOUR = 120f

    /** Hours past the viewport, already loaded so sideways scroll has programs. */
    const val SCROLL_AHEAD_HOURS = 8

    /** Hard stop for a very wide display and for the per-channel cache. */
    const val MAX_HOURS = 18

    /** Xtream EPG rows kept in memory. Older channels are dropped and fetched again. */
    const val CACHE_CHANNELS = 240

    /**
     * Listings requested per channel. Two per hour covers half-hour slots
     * across [MAX_HOURS], plus the show already in progress.
     */
    const val LISTING_LIMIT = MAX_HOURS * 2 + 2

    private const val HALF_HOUR_MS = 30L * 60L * 1000L

    /** Start of the grid: the half-hour before the current half-hour. */
    fun snapStart(nowMs: Long): Long {
        if (nowMs <= 0L) return 0L
        return nowMs - (nowMs % HALF_HOUR_MS) - HALF_HOUR_MS
    }

    fun visibleHours(timelineWidthDp: Float, dpPerHour: Float = DP_PER_HOUR): Int {
        if (timelineWidthDp <= 0f || dpPerHour <= 0f) return 1
        return ceil(timelineWidthDp / dpPerHour.toDouble()).toInt().coerceIn(1, MAX_HOURS)
    }

    fun totalHours(timelineWidthDp: Float, dpPerHour: Float = DP_PER_HOUR): Int {
        val visible = visibleHours(timelineWidthDp, dpPerHour)
        return (visible + SCROLL_AHEAD_HOURS).coerceAtMost(MAX_HOURS).coerceAtLeast(visible)
    }

    fun windowEndMs(windowStartMs: Long, totalHours: Int): Long =
        windowStartMs + totalHours.coerceAtLeast(1) * 60L * 60L * 1000L

    /** Keep programs that overlap the horizon. Everything else is dropped. */
    fun retain(programs: List<EpgProgram>, windowStartMs: Long, windowEndMs: Long): List<EpgProgram> {
        if (windowEndMs <= windowStartMs) return emptyList()
        return programs.filter { it.endMs > windowStartMs && it.startMs < windowEndMs }
    }

    /**
     * Drop about half the cached channels once [cache] is full, unless [incomingKey]
     * is already stored. Callers can re-fetch a channel that was evicted.
     */
    fun <V> trimChannelCache(cache: MutableMap<Int, V>, incomingKey: Int, maxChannels: Int = CACHE_CHANNELS) {
        if (maxChannels < 1) return
        if (cache.size < maxChannels || cache.containsKey(incomingKey)) return
        val dropCount = (maxChannels / 2).coerceAtLeast(1)
        cache.keys.take(dropCount).forEach { cache.remove(it) }
    }
}
