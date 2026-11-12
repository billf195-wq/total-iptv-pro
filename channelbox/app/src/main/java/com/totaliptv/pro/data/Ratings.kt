package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.MediaItem
import java.util.Locale

/** Provider rating parsing / ordering shared by Xtream, M3U and the "Top rated" sort. */
object Ratings {
    /** Ignore TMDB ratings with fewer votes than this so obscure titles cannot top the list. */
    const val TMDB_MIN_VOTE_COUNT = 50

    private val leadingNumber = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(?:/\s*(\d+(?:[.,]\d+)?))?""")

    /**
     * Xtream: prefer `rating` (/10), fall back to `rating_5based` x2. Returns "8.2"-style
     * text or null for blank / zero / junk.
     */
    fun normalizeXtream(rating: String?, rating5based: String?): String? {
        val raw = rating?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("N/A", true) && it != "0" && it != "0.0"
        }
        val asNum = raw?.replace(',', '.')?.toFloatOrNull()
        if (asNum != null) {
            if (asNum <= 0f) return null
            return String.format(Locale.US, "%.1f", asNum)
        }
        val r5 = rating5based?.trim()?.replace(',', '.')?.toFloatOrNull()
        if (r5 != null && r5 > 0f) {
            val outOfTen = if (r5 <= 5.01f) r5 * 2f else r5
            return String.format(Locale.US, "%.1f", outOfTen)
        }
        return null
    }

    /**
     * M3U `tvg-rating` / `rating`: numeric only ("7.4", "7,4", "3.5/5", "74/100").
     * Age ratings like "PG-13" / "TV-MA" return null so they never show as a score.
     */
    fun normalizeM3u(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        val m = leadingNumber.find(t) ?: return null
        // Reject "PG-13"-style values: the number must start the string.
        if (m.range.first != 0 && t.substring(0, m.range.first).isNotBlank()) return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val scale = m.groupValues[2].replace(',', '.').toDoubleOrNull()
        val tenBased = when {
            scale != null && scale > 0.0 -> v / scale * 10.0
            v > 10.0 && v <= 100.0 -> v / 10.0
            else -> v
        }
        if (tenBased <= 0.0 || tenBased > 10.0) return null
        return String.format(Locale.US, "%.1f", tenBased)
    }

    /**
     * Usable TMDB score (0–10) when [voteCount] is at least [TMDB_MIN_VOTE_COUNT].
     * Null for missing, zero, out of range, or too few votes.
     */
    fun tmdbScore(voteAverage: Double?, voteCount: Int?): Double? {
        if (voteAverage == null || voteCount == null) return null
        if (voteCount < TMDB_MIN_VOTE_COUNT) return null
        return voteAverage.takeIf { it > 0.0 && it <= 10.0 }
    }

    /** "8.2"-style text for a 0–10 score; null when [score] is not usable. */
    fun formatTen(score: Double?): String? {
        val v = score ?: return null
        if (v <= 0.0 || v > 10.0) return null
        return String.format(Locale.US, "%.1f", v)
    }

    fun tmdbDisplay(voteAverage: Double?, voteCount: Int?): String? =
        formatTen(tmdbScore(voteAverage, voteCount))

    /**
     * Numeric /10 score for sorting; null = unrated.
     * When [tmdbOnly] is true (a TMDB key is set), only [tmdbScore] counts and the
     * provider rating is ignored. When [tmdbOnly] is false (no key), the provider
     * rating is used and [tmdbScore] is ignored.
     */
    fun score(item: MediaItem, tmdbScore: Double? = null, tmdbOnly: Boolean = false): Double? {
        if (tmdbOnly) return tmdbScore?.takeIf { it > 0.0 && it <= 10.0 }
        return providerScore(item)
    }

    private fun providerScore(item: MediaItem): Double? {
        val v = item.displayRating()?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val tenBased = if (v > 10.0 && v <= 100.0) v / 10.0 else v
        return tenBased.takeIf { it > 0.0 && it <= 10.0 }
    }

    /**
     * Copy [score] onto the item for poster badges. When [overwriteProvider] is true
     * (TMDB-only mode), a TMDB score replaces the provider rating on the copy.
     */
    fun withMergedRating(item: MediaItem, score: Double?, overwriteProvider: Boolean = false): MediaItem {
        if (score == null) return item
        if (!overwriteProvider && item.displayRating() != null) return item
        return formatTen(score)?.let { item.copy(rating = it) } ?: item
    }

    /** Rated titles only, highest first, ties A–Z, capped at [limit]. */
    fun topRatedOnly(
        items: List<MediaItem>,
        limit: Int,
        tmdbScore: ((MediaItem) -> Double?)? = null
    ): List<MediaItem> {
        val tmdbOnly = tmdbScore != null
        return items.mapNotNull { item ->
            score(item, tmdbScore?.invoke(item), tmdbOnly = tmdbOnly)?.let { item to it }
        }
            .sortedWith(compareByDescending<Pair<MediaItem, Double>> { it.second }.thenBy { it.first.name.lowercase() })
            .take(limit)
            .map { withMergedRating(it.first, it.second, overwriteProvider = tmdbOnly) }
    }

    /** Highest rating first, unrated at the bottom, ties A–Z. */
    fun sortTopRated(
        items: List<MediaItem>,
        tmdbScore: ((MediaItem) -> Double?)? = null
    ): List<MediaItem> {
        val tmdbOnly = tmdbScore != null
        val scored = items.map { it to score(it, tmdbScore?.invoke(it), tmdbOnly = tmdbOnly) }
        return scored.sortedWith(
            compareBy<Pair<MediaItem, Double?>> { if (it.second == null) 1 else 0 }
                .thenByDescending { it.second ?: 0.0 }
                .thenBy { it.first.name.lowercase() }
        ).map { withMergedRating(it.first, it.second, overwriteProvider = tmdbOnly) }
    }
}
