package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Picks one playable, top-rated movie for the Classic Home hero, once a day.
 * Prefers American titles when the provider sent origin/country data.
 */
object HeroBannerPick {
    const val FILE_NAME = "hero-banner-pick.txt"

    data class Stored(val day: String, val itemId: String)

    fun dayString(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone).toString()

    fun isPlayableMovie(item: MediaItem): Boolean =
        item.kind == ContentKind.VOD && item.streamUrl.isNotBlank()

    fun isAmerican(country: String?): Boolean {
        val raw = country?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val lower = raw.lowercase()
        if (lower.contains("united states")) return true
        val tokens = lower.split(Regex("[,/|&]")).map { it.trim() }.filter { it.isNotEmpty() }
        return tokens.any { it in AMERICAN_TOKENS }
    }

    fun imageUrl(item: MediaItem): String? =
        item.backdropUrl?.takeIf { it.isNotBlank() } ?: item.artworkUrl()

    fun metaLine(item: MediaItem): String? {
        val year = item.year?.takeIf { it in 1870..2100 }?.toString()
        val rating = item.displayRating()?.let { "★ $it" }
        return listOfNotNull(year, rating).takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
    }

    fun pick(candidates: List<MediaItem>, day: String, stored: Stored?): MediaItem? {
        val playable = candidates.filter { isPlayableMovie(it) }
        if (playable.isEmpty()) return null
        if (stored != null && stored.day == day) {
            playable.find { it.id == stored.itemId }?.let { return it }
        }
        val american = playable.filter { isAmerican(it.country) }
        val pool = if (american.isNotEmpty()) american else playable
        return pool[indexFor(day, pool.size)]
    }

    fun indexFor(day: String, size: Int): Int {
        if (size <= 0) return 0
        var h = 0
        for (c in day) h = 31 * h + c.code
        val idx = h % size
        return if (idx < 0) idx + size else idx
    }

    fun read(dir: File): Stored? {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return null
        val line = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull() ?: return null
        val parts = line.split('\t')
        if (parts.size != 2) return null
        val day = parts[0].trim()
        val id = parts[1].trim()
        if (day.isEmpty() || id.isEmpty()) return null
        return Stored(day, id)
    }

    fun write(dir: File, stored: Stored) {
        dir.mkdirs()
        File(dir, FILE_NAME).writeText("${stored.day}\t${stored.itemId}", Charsets.UTF_8)
    }

    private val AMERICAN_TOKENS = setOf(
        "us", "usa", "u.s.", "u.s.a.", "u.s", "u.s.a",
        "america", "american"
    )
}
