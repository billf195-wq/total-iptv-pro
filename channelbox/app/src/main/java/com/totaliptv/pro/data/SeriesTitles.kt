package com.totaliptv.pro.data

/**
 * One line for the player. Xtream episode titles often already contain the
 * series name or "S01E01", which used to be appended a second time.
 */
object SeriesTitles {
    fun label(seriesName: String, season: Int, episode: Int, episodeTitle: String): String {
        val series = seriesName.substringBefore(" — ").trim()
        val ep = episodeTitle.trim()
        val code = "S${season.coerceAtLeast(0)}E${episode.coerceAtLeast(0)}"
        if (ep.isBlank()) return listOf(series, code).filter { it.isNotBlank() }.joinToString(" — ")
        if (series.isNotBlank() && ep.startsWith(series, ignoreCase = true)) return ep
        val alreadyCoded = ep.contains(code, ignoreCase = true) ||
            ep.contains("S%02dE%02d".format(season, episode), ignoreCase = true)
        if (alreadyCoded) {
            return if (series.isBlank() || ep.contains(series, ignoreCase = true)) ep else "$series — $ep"
        }
        return listOf(series, "$code $ep".trim()).filter { it.isNotBlank() }.joinToString(" — ")
    }
}
