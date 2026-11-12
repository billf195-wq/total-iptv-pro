package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.MediaItem
import java.util.Locale

/** Provider rating parsing / ordering shared by Xtream, M3U and the "Top rated" sort. */
object Ratings {
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

    /** Numeric /10 score for sorting; null = unrated. */
    fun score(item: MediaItem): Double? {
        val v = item.displayRating()?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val tenBased = if (v > 10.0 && v <= 100.0) v / 10.0 else v
        return tenBased.takeIf { it > 0.0 && it <= 10.0 }
    }

    /** Highest rating first, unrated at the bottom, ties A–Z. */
    fun sortTopRated(items: List<MediaItem>): List<MediaItem> {
        val scored = items.map { it to score(it) }
        return scored.sortedWith(
            compareBy<Pair<MediaItem, Double?>> { if (it.second == null) 1 else 0 }
                .thenByDescending { it.second ?: 0.0 }
                .thenBy { it.first.name.lowercase() }
        ).map { it.first }
    }
}
