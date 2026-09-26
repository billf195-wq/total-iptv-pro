package com.totaliptv.pro.ui.desktop

import com.totaliptv.pro.data.model.MediaItem
import java.util.Calendar

private const val TOP_N = 24
private const val MIN_FILTERED = 6
private const val NEW_YEAR_WINDOW = 5
private const val NEW_ADDED_WINDOW_YEARS = 5L
/** Cap main-thread-adjacent ranking scans so huge VOD catalogs cannot ANR Home. */
private const val SCAN_CAP = 2500

data class TopRatedRow(val title: String, val items: List<MediaItem>)

/** Process-wide cache so Movies→Home with unchanged revision skips ranking entirely. */
object TopRatedCache {
    @Volatile private var revision: Int = Int.MIN_VALUE
    @Volatile private var movies: TopRatedRow? = null
    @Volatile private var series: TopRatedRow? = null

    fun movies(revision: Int): TopRatedRow? = movies.takeIf { this.revision == revision }
    fun series(revision: Int): TopRatedRow? = series.takeIf { this.revision == revision }

    fun put(revision: Int, movies: TopRatedRow, series: TopRatedRow) {
        this.movies = movies
        this.series = series
        this.revision = revision
    }

    fun clear() {
        revision = Int.MIN_VALUE
        movies = null
        series = null
    }
}

private fun MediaItem.ratingScore(): Double =
    displayRating()?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 0.0

fun isLikelyAmerican(item: MediaItem): Boolean {
    val group = item.groupTitle?.lowercase().orEmpty()
    if (Regex("""\b(us|u\.s\.?|usa|american)\b""").containsMatchIn(group) &&
        !Regex("""\b(ussr|ukraine|australia|austria)\b""").containsMatchIn(group)
    ) return true
    val name = item.name.lowercase()
    if (Regex("""\b(usa|u\.s\.a\.?)\b""").containsMatchIn(name)) return true
    return false
}

fun guessReleaseYear(item: MediaItem): Int? {
    Regex("""\((19|20)\d{2}\)""").find(item.name)?.groupValues?.get(0)
        ?.trim('(', ')')?.toIntOrNull()?.let { return it }
    Regex("""(?:^|[\s\-–—])((?:19|20)\d{2})\s*$""").find(item.name)?.groupValues?.get(1)
        ?.toIntOrNull()?.let { return it }
    return null
}

fun isLikelyNew(
    item: MediaItem,
    nowEpochSec: Long = System.currentTimeMillis() / 1000L,
    currentYear: Int = Calendar.getInstance().get(Calendar.YEAR)
): Boolean {
    val year = guessReleaseYear(item)
    if (year != null) return year >= (currentYear - NEW_YEAR_WINDOW)
    val addedMs = item.addedMs
    if (addedMs != null && addedMs > 0L) {
        val addedSec = if (addedMs > 10_000_000_000L) addedMs / 1000L else addedMs
        val cutoff = nowEpochSec - (NEW_ADDED_WINDOW_YEARS * 365L * 24L * 3600L)
        return addedSec >= cutoff
    }
    return false
}

/** Cap + prefer rated titles so ranking stays O(SCAN_CAP), not full-catalog O(n log n). */
private fun cappedScan(items: List<MediaItem>): List<MediaItem> {
    if (items.size <= SCAN_CAP) return items
    val out = ArrayList<MediaItem>(SCAN_CAP)
    for (it in items) {
        if (it.ratingScore() > 0.0) {
            out.add(it)
            if (out.size >= SCAN_CAP) return out
        }
    }
    if (out.size < SCAN_CAP) {
        val need = SCAN_CAP - out.size
        val start = (items.size - need).coerceAtLeast(0)
        for (i in start until items.size) {
            if (items[i].ratingScore() <= 0.0) out.add(items[i])
            if (out.size >= SCAN_CAP) break
        }
    }
    return out
}

private fun topByRating(list: List<MediaItem>): List<MediaItem> {
    if (list.size <= TOP_N) return list.sortedByDescending { it.ratingScore() }
    // Partial top-N without sorting the entire list when still large.
    return list.asSequence()
        .sortedByDescending { it.ratingScore() }
        .take(TOP_N)
        .toList()
}

fun pickTopRatedMovies(items: List<MediaItem>): TopRatedRow {
    val scan = cappedScan(items)
    val rated = scan.filter { it.ratingScore() > 0.0 }
    val pool = rated.ifEmpty { scan }

    val americanNew = topByRating(pool.filter { isLikelyAmerican(it) && isLikelyNew(it) })
    if (americanNew.size >= MIN_FILTERED) {
        return TopRatedRow("Top rated new American movies", americanNew)
    }
    val newOnly = topByRating(pool.filter { isLikelyNew(it) })
    if (newOnly.size >= MIN_FILTERED) {
        return TopRatedRow("Top rated new movies", newOnly)
    }
    val overall = topByRating(pool)
    return TopRatedRow(
        "Top rated movies",
        overall.ifEmpty { scan.sortedByDescending { it.addedMs ?: 0L }.take(TOP_N) }
    )
}

fun pickTopRatedSeries(items: List<MediaItem>): TopRatedRow {
    val scan = cappedScan(items)
    val rated = scan.filter { it.ratingScore() > 0.0 }
    val pool = rated.ifEmpty { scan }

    val americanNew = topByRating(pool.filter { isLikelyAmerican(it) && isLikelyNew(it) })
    if (americanNew.size >= MIN_FILTERED) {
        return TopRatedRow("Top rated new American series", americanNew)
    }
    val newOnly = topByRating(pool.filter { isLikelyNew(it) })
    if (newOnly.size >= MIN_FILTERED) {
        return TopRatedRow("Top rated new series", newOnly)
    }
    val overall = topByRating(pool)
    return TopRatedRow(
        "Top rated series",
        overall.ifEmpty { scan.sortedByDescending { it.addedMs ?: 0L }.take(TOP_N) }
    )
}
