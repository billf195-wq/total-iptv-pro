package com.totaliptv.pro2.data

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
 * Same rules as desktop 1.2.11 / ChannelBox: unix when it agrees with
 * OS/provider wall time; naive text in the device/provider zone;
 * local-as-unix corrected; [alignToNow] for the row that contains "now".
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

    fun fromFields(
        timestamp: String?,
        text: String?,
        displayZone: ZoneId = ZoneId.systemDefault(),
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
                    !isUtcLike(providerZone) &&
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
        candidates += ZoneId.systemDefault()
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
        ).forEach { id -> zoneOrNull(id)?.let { candidates += it } }
        return candidates.firstOrNull { z ->
            near(target, fromNaiveInZone(timeNow, z))
        }
    }

    fun zoneOrNull(id: String): ZoneId? =
        runCatching { ZoneId.of(id.trim()) }.getOrNull()

    fun isUtcLike(zone: ZoneId): Boolean {
        val id = zone.id
        if (id.equals("UTC", true) || id.equals("Etc/UTC", true) ||
            id.equals("GMT", true) || id.equals("Z", true)
        ) {
            return true
        }
        val rules = zone.rules
        return rules.isFixedOffset && rules.getOffset(Instant.EPOCH).totalSeconds == 0
    }
}
