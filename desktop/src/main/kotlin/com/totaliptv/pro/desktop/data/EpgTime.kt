package com.totaliptv.pro.desktop.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Parse Xtream EPG `start_timestamp` / `start` into UTC epoch ms.
 *
 * Xtream `player_api.php` sets `start_timestamp` from the EPG unix value and
 * formats `start` with PHP `date()` in the **panel timezone** (`server_info`).
 *
 * 1.2.10 treated naive `yyyy-MM-dd HH:mm:ss` as UTC. Android / ChannelBox still
 * parse that string with the device timezone. On a Central PC that UTC guess
 * shifts every block ~5 hours vs the now-line (real epoch).
 *
 * Rules:
 * - Unix `start_timestamp` is a UTC instant when it agrees with the text in
 *   UTC, the OS zone, or the provider zone.
 * - Naive text (no usable timestamp) uses the OS/provider zone — same as Android.
 * - If the timestamp equals text-as-UTC but not text-as-provider (local wall
 *   stuffed into the unix field), use the provider/OS wall time.
 * - [alignToNow] picks the interpretation that actually contains "now".
 */
object EpgTime {
    private val NAIVE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    internal const val AGREE_MS: Long = 120_000L

    data class Instants(val startMs: Long, val endMs: Long) {
        fun contains(nowMs: Long): Boolean = nowMs in startMs until endMs
    }

    fun fromUnixOrMillis(ts: Long): Long {
        if (ts <= 0L) return 0L
        return if (ts < 10_000_000_000L) ts * 1000L else ts
    }

    fun fromNaiveInZone(text: String, zone: ZoneId): Long {
        val raw = text.trim()
        if (raw.isEmpty()) return 0L
        runCatching { return Instant.parse(raw).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(raw).toInstant().toEpochMilli() }
        val normalized = raw.take(19).replace('T', ' ')
        return try {
            val parsed = LocalDateTime.parse(normalized, NAIVE)
            parsed.atZone(zone).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    fun fromText(text: String, zone: ZoneId = GuideTime.zone()): Long = fromNaiveInZone(text, zone)

    fun fromFields(
        timestamp: String?,
        text: String?,
        displayZone: ZoneId = GuideTime.zone(),
        providerZone: ZoneId? = null
    ): Long {
        val tsMs = timestamp?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { fromUnixOrMillis(it) }
        val textZone = providerZone ?: displayZone
        val textLocal = text?.let { fromNaiveInZone(it, textZone) }?.takeIf { it > 0L }
        val textDisplay = text?.let { fromNaiveInZone(it, displayZone) }?.takeIf { it > 0L }
        val textUtc = text?.let { fromNaiveInZone(it, ZoneOffset.UTC) }?.takeIf { it > 0L }

        if (tsMs != null && tsMs > 0L) {
            if (near(tsMs, textLocal) || near(tsMs, textDisplay)) {
                return tsMs
            }
            if (near(tsMs, textUtc)) {
                if (providerZone != null &&
                    !OsTimeZone.isUtcLike(providerZone) &&
                    textLocal != null &&
                    !near(tsMs, textLocal)
                ) {
                    return textLocal
                }
                return tsMs
            }
            return tsMs
        }
        return textLocal ?: textDisplay ?: textUtc ?: 0L
    }

    fun near(a: Long, b: Long?, tolMs: Long = AGREE_MS): Boolean =
        b != null && b > 0L && abs(a - b) <= tolMs

    /**
     * If the timestamp-based row has no "now" program but the OS-local text
     * row does, the unix field was local wall mislabeled as UTC.
     */
    fun alignToNow(
        primary: List<Instants>,
        localText: List<Instants>,
        nowMs: Long
    ): List<Instants> {
        if (primary.size != localText.size || primary.isEmpty()) return primary
        val primaryHits = primary.count { it.contains(nowMs) }
        val localHits = localText.count { it.contains(nowMs) }
        return if (primaryHits == 0 && localHits > 0) localText else primary
    }

    fun inferProviderZone(timeNow: String?, timestampNow: Long?): ZoneId? {
        if (timeNow.isNullOrBlank() || timestampNow == null || timestampNow <= 0L) return null
        val target = fromUnixOrMillis(timestampNow)
        val candidates = linkedSetOf<ZoneId>()
        GuideTime.zone().let { candidates += it }
        candidates += ZoneOffset.UTC
        listOf(
            "America/Chicago",
            "America/New_York",
            "America/Denver",
            "America/Los_Angeles",
            "America/Phoenix",
            "Europe/London",
            "Europe/Amsterdam",
            "Europe/Berlin"
        ).forEach { id -> OsTimeZone.zoneOrNull(id)?.let { candidates += it } }
        return candidates.firstOrNull { z ->
            near(target, fromNaiveInZone(timeNow, z))
        }
    }
}
