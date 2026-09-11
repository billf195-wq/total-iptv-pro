package com.totaliptv.pro2.ui

import com.totaliptv.pro2.data.Catalog
import com.totaliptv.pro2.data.MediaItem

private const val TOP_N = 24
private const val MIN_FILTERED = 6
private const val NEW_YEAR_WINDOW = 5
private const val NEW_ADDED_WINDOW_YEARS = 5L

data class TopRatedRow(val title: String, val items: List<MediaItem>)

fun isLikelyAmerican(item: MediaItem): Boolean {
    val country = item.country?.lowercase()?.trim().orEmpty()
    if (country.isNotEmpty()) {
        if (country.contains("united states") || country.contains("usa")) return true
        val tokens = country.split(Regex("""[,;/|]+|\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.any {
                it == "us" || it == "u.s" || it == "u.s." || it == "u.s.a" || it == "u.s.a." ||
                    it == "usa" || it == "united states" || it == "united states of america"
            }) return true
    }
    val group = item.groupTitle?.lowercase().orEmpty()
    if (Regex("""\b(us|u\.s\.?|usa|american)\b""").containsMatchIn(group) &&
        !Regex("""\b(ussr|ukraine|australia|austria)\b""").containsMatchIn(group)
    ) return true
    return false
}

fun guessReleaseYear(item: MediaItem): Int? {
    item.year?.takeIf { it in 1888..2100 }?.let { return it }
    Regex("""\((19|20)\d{2}\)""").find(item.name)?.groupValues?.get(0)
        ?.trim('(', ')')?.toIntOrNull()?.let { return it }
    Regex("""(?:^|[\s\-–—])((?:19|20)\d{2})\s*$""").find(item.name)?.groupValues?.get(1)
        ?.toIntOrNull()?.let { return it }
    return null
}

fun isLikelyNew(
    item: MediaItem,
    nowEpochSec: Long = System.currentTimeMillis() / 1000L,
    currentYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
): Boolean {
    val year = guessReleaseYear(item)
    if (year != null) return year >= (currentYear - NEW_YEAR_WINDOW)
    val added = item.addedEpoch
    if (added > 0L) {
        val cutoff = nowEpochSec - (NEW_ADDED_WINDOW_YEARS * 365L * 24L * 3600L)
        return added >= cutoff
    }
    return false
}

fun pickTopRatedMovies(catalog: Catalog): TopRatedRow {
    val rated = catalog.vodItems.filter { it.ratingScore() > 0.0 }
    val pool = rated.ifEmpty { catalog.vodItems }
    fun byRating(list: List<MediaItem>) = list.sortedByDescending { it.ratingScore() }.take(TOP_N)

    val americanNew = byRating(pool.filter { isLikelyAmerican(it) && isLikelyNew(it) })
    if (americanNew.size >= MIN_FILTERED) {
        return TopRatedRow("Top rated new American movies", americanNew)
    }
    val newOnly = byRating(pool.filter { isLikelyNew(it) })
    if (newOnly.size >= MIN_FILTERED) {
        return TopRatedRow("Top rated new movies", newOnly)
    }
    val overall = byRating(pool)
    return TopRatedRow(
        "Top rated movies",
        overall.ifEmpty { catalog.vodItems.sortedByDescending { it.addedEpoch }.take(TOP_N) }
    )
}


fun pickTopRatedSeries(catalog: Catalog): TopRatedRow {
    val rated = catalog.seriesItems.filter { it.ratingScore() > 0.0 }
    val pool = rated.ifEmpty { catalog.seriesItems }
    fun byRating(list: List<MediaItem>) = list.sortedByDescending { it.ratingScore() }.take(TOP_N)

    val americanNew = byRating(pool.filter { isLikelyAmerican(it) && isLikelyNew(it) })
    if (americanNew.size >= MIN_FILTERED) {
        return TopRatedRow("Top rated new American series", americanNew)
    }
    val newOnly = byRating(pool.filter { isLikelyNew(it) })
    if (newOnly.size >= MIN_FILTERED) {
        return TopRatedRow("Top rated new series", newOnly)
    }
    val overall = byRating(pool)
    return TopRatedRow(
        "Top rated series",
        overall.ifEmpty { catalog.seriesItems.sortedByDescending { it.addedEpoch }.take(TOP_N) }
    )
}
