package com.totaliptv.pro.desktop.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * TV Guide clock, hour ticks, program ranges, and now-line.
 *
 * Always uses the **computer timezone** ([ZoneId.systemDefault]) — never a
 * hardcoded IANA zone. If the OS is set to Central, the lineup shows Central;
 * change the OS zone and the guide follows.
 *
 * Epoch milliseconds are UTC instants. Display converts Instant → local zone.
 * Do not treat epoch ms as a local wall-clock LocalDateTime.
 */
object GuideTime {
    val CLOCK_PATTERN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d · h:mm a")
    val HOUR_TICK_PATTERN: DateTimeFormatter = DateTimeFormatter.ofPattern("h a")
    val TIME_PATTERN: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun nowMs(): Long = System.currentTimeMillis()

    fun formatClock(ms: Long = nowMs(), zone: ZoneId = zone()): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(CLOCK_PATTERN)

    fun formatHourTick(ms: Long, zone: ZoneId = zone()): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(HOUR_TICK_PATTERN)

    fun formatTime(ms: Long, zone: ZoneId = zone()): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(TIME_PATTERN)

    fun formatRange(startMs: Long, endMs: Long, zone: ZoneId = zone()): String =
        "${formatTime(startMs, zone)} – ${formatTime(endMs, zone)}"

    /** Floor [ms] to the local hour in [zone] (DST-safe). */
    fun alignToLocalHour(ms: Long, zone: ZoneId = zone()): Long =
        Instant.ofEpochMilli(ms).atZone(zone).truncatedTo(ChronoUnit.HOURS)
            .toInstant().toEpochMilli()

    /**
     * Hour-boundary ticks from [windowStart] to [windowEnd] in [zone].
     * Used for the timeline header so labels sit on local hours, not now-30min.
     */
    fun hourTicks(windowStart: Long, windowEnd: Long, zone: ZoneId = zone()): List<Long> {
        if (windowEnd <= windowStart) return emptyList()
        val out = ArrayList<Long>()
        var t = alignToLocalHour(windowStart, zone)
        if (t < windowStart) {
            t = Instant.ofEpochMilli(t).atZone(zone).plusHours(1).toInstant().toEpochMilli()
        }
        while (t < windowEnd) {
            out += t
            t = Instant.ofEpochMilli(t).atZone(zone).plusHours(1).toInstant().toEpochMilli()
        }
        return out
    }
}
