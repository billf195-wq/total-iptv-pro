package com.totaliptv.pro.desktop.data

/**
 * Shared series season / next-episode logic for Live desktop (Linux + Windows).
 * Order is always season then episode number — never list index.
 */
object SeriesPlayback {
    fun sortedEpisodes(episodes: List<SeriesEpisode>): List<SeriesEpisode> =
        episodes.sortedWith(compareBy({ it.season }, { it.episodeNum }, { it.id }))

    fun seasonNumbers(episodes: List<SeriesEpisode>): List<Int> =
        episodes.map { it.season }.distinct().sorted()

    fun inSeason(episodes: List<SeriesEpisode>, season: Int?): List<SeriesEpisode> {
        val sorted = sortedEpisodes(episodes)
        return if (season == null) sorted else sorted.filter { it.season == season }
    }

    fun findEpisode(
        episodes: List<SeriesEpisode>,
        season: Int?,
        episodeNum: Int?
    ): SeriesEpisode? {
        if (season == null || episodeNum == null) return null
        return sortedEpisodes(episodes).find { it.season == season && it.episodeNum == episodeNum }
    }

    /** Episode after [season]/[episodeNum], or null at end of the series. */
    fun nextEpisode(
        episodes: List<SeriesEpisode>,
        season: Int?,
        episodeNum: Int?
    ): SeriesEpisode? {
        val sorted = sortedEpisodes(episodes)
        if (sorted.isEmpty()) return null
        if (season == null || episodeNum == null) return sorted.first()
        val idx = sorted.indexOfFirst { it.season == season && it.episodeNum == episodeNum }
        if (idx >= 0) return sorted.getOrNull(idx + 1)
        return sorted.firstOrNull { ep ->
            ep.season > season || (ep.season == season && ep.episodeNum > episodeNum)
        }
    }

    /** Last watched episode if still in the list, else first episode. */
    fun continueEpisode(
        episodes: List<SeriesEpisode>,
        season: Int?,
        episodeNum: Int?
    ): SeriesEpisode? {
        findEpisode(episodes, season, episodeNum)?.let { return it }
        nextEpisode(episodes, season, episodeNum)?.let { return it }
        return sortedEpisodes(episodes).firstOrNull()
    }
}
