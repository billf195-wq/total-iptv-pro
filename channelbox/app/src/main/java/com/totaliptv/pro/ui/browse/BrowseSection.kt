package com.totaliptv.pro.ui.browse

enum class BrowseSection {
    Live,
    Movies,
    Series,
    Favorites
}

/** Shared heuristic for series-named VOD categories (honest empty when none). */
fun looksLikeSeriesCategory(name: String): Boolean {
    val n = name.lowercase()
    return n.contains("series") ||
        n.contains("tv show") ||
        n.contains("tvshow") ||
        n.contains("saison") ||
        (n.contains("show") && !n.contains("showcase"))
}
