package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Picks one playable, genuinely top-rated movie for the Classic Home hero, once a day.
 * Implausible scores (>= 9.5) and known low vote counts are skipped. The pool is
 * well-known titles (about 7.0-9.4). Within that top set, prefers American titles
 * when origin/country data exists. Daily rotation is deterministic and stays inside
 * that top set.
 */
object HeroBannerPick {
    const val FILE_NAME = "hero-banner-pick.txt"
    /** Titles this close to the highest eligible score count as the top set. */
    const val TOP_SET_DELTA = 0.3
    const val TOP_SET_CAP = 10
    /** Inclusive floor for a "well-known" hero rating. */
    const val MIN_PLAUSIBLE_RATING = 7.0
    /** Ratings at or above this are treated as implausible and skipped. */
    const val IMPLAUSIBLE_RATING = 9.5
    /** Skip a title only when a vote count is known and is below this floor. */
    const val MIN_VOTE_COUNT = 50

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

    /** Provider / badge rating on a 0–10 scale; null if unrated. */
    fun ratingNumber(item: MediaItem): Double? {
        val v = item.displayRating()?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val ten = if (v > 10.0 && v <= 100.0) v / 10.0 else v
        return ten.takeIf { it > 0.0 && it <= 10.0 }
    }

    /** True when a known vote count is below [MIN_VOTE_COUNT]. Missing counts do not disqualify. */
    fun hasTooFewVotes(item: MediaItem): Boolean {
        val votes = item.voteCount ?: return false
        return votes < MIN_VOTE_COUNT
    }

    fun isPlausibleRating(rating: Double): Boolean =
        rating >= MIN_PLAUSIBLE_RATING && rating < IMPLAUSIBLE_RATING

    fun isEligible(item: MediaItem): Boolean {
        if (!isPlayableMovie(item)) return false
        if (hasTooFewVotes(item)) return false
        val rating = ratingNumber(item) ?: return false
        return isPlausibleRating(rating)
    }

    /**
     * Highest-rated eligible cluster, American-preferred when those titles
     * themselves sit in the top set. Capped at [TOP_SET_CAP].
     */
    fun topSet(candidates: List<MediaItem>): List<MediaItem> {
        val preferred = candidates.filter { isEligible(it) }
        val playable = preferred.ifEmpty {
            // Nothing in the 7.0–9.4 band: still skip implausible scores and low votes.
            candidates.filter { item ->
                isPlayableMovie(item) &&
                    !hasTooFewVotes(item) &&
                    (ratingNumber(item) ?: Double.NEGATIVE_INFINITY) < IMPLAUSIBLE_RATING
            }
        }
        if (playable.isEmpty()) return emptyList()
        val scored = playable.map { it to (ratingNumber(it) ?: Double.NEGATIVE_INFINITY) }
        val max = scored.maxOf { it.second }
        val clustered = if (max == Double.NEGATIVE_INFINITY) {
            playable.take(TOP_SET_CAP)
        } else {
            scored.filter { it.second >= max - TOP_SET_DELTA }
                .sortedWith(compareByDescending<Pair<MediaItem, Double>> { it.second }.thenBy { it.first.id })
                .map { it.first }
                .take(TOP_SET_CAP)
        }
        val american = clustered.filter { isAmerican(it.country) }
        return if (american.isNotEmpty()) american else clustered
    }

    fun pick(candidates: List<MediaItem>, day: String, stored: Stored?): MediaItem? {
        val pool = topSet(candidates)
        if (pool.isEmpty()) return null
        if (stored != null && stored.day == day) {
            pool.find { it.id == stored.itemId }?.let { return it }
        }
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
