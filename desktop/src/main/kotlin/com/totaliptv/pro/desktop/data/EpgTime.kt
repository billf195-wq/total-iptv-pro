package com.totaliptv.pro.desktop.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Parse Xtream EPG `start_timestamp` / `start` (and stop/end) into UTC epoch ms.
 *
 * Unix timestamps are UTC. Naive `yyyy-MM-dd HH:mm:ss` from Xtream is produced
 * from those unix values (PHP `date()` on UTC panels) — treat as UTC wall time,
 * not the computer's local zone. Display then uses [GuideTime] / systemDefault.
 */
object EpgTime {
    private val NAIVE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun fromUnixOrMillis(ts: Long): Long {
        if (ts <= 0L) return 0L
        return if (ts < 10_000_000_000L) ts * 1000L else ts
    }

    fun fromText(text: String): Long {
        val raw = text.trim()
        if (raw.isEmpty()) return 0L
        runCatching { return Instant.parse(raw).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(raw).toInstant().toEpochMilli() }
        val normalized = raw.take(19).replace('T', ' ')
        return try {
            val parsed = LocalDateTime.parse(normalized, NAIVE)
            parsed.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    fun fromFields(timestamp: String?, text: String?): Long {
        val ts = timestamp?.trim()?.toLongOrNull()
        if (ts != null && ts > 0L) return fromUnixOrMillis(ts)
        if (!text.isNullOrBlank()) return fromText(text)
        return 0L
    }
}
