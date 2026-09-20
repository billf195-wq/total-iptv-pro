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

    fun normalizeEpisodeId(raw: String?): String? {
        val id = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return id.removePrefix("ep-").removePrefix("series-ep-")
    }

    fun matchesEpisodeId(episode: SeriesEpisode, rawId: String?): Boolean {
        val want = normalizeEpisodeId(rawId) ?: return false
        val have = normalizeEpisodeId(episode.id) ?: episode.id
        return have == want || episode.id == rawId
    }

    fun indexOfEpisode(
        episodes: List<SeriesEpisode>,
        season: Int?,
        episodeNum: Int?,
        episodeId: String? = null
    ): Int {
        val sorted = sortedEpisodes(episodes)
        if (sorted.isEmpty()) return -1
        val byId = normalizeEpisodeId(episodeId)?.let { id ->
            sorted.indexOfFirst { matchesEpisodeId(it, id) }
        } ?: -1
        if (byId >= 0) return byId
        if (season == null || episodeNum == null) return -1
        return sorted.indexOfFirst { it.season == season && it.episodeNum == episodeNum }
    }

    fun findEpisode(
        episodes: List<SeriesEpisode>,
        season: Int?,
        episodeNum: Int?,
        episodeId: String? = null
    ): SeriesEpisode? {
        val sorted = sortedEpisodes(episodes)
        val idx = indexOfEpisode(sorted, season, episodeNum, episodeId)
        return sorted.getOrNull(idx)
    }

    /** Episode after the given position, or null at end of the series. */
    fun nextEpisode(
        episodes: List<SeriesEpisode>,
        season: Int?,
        episodeNum: Int?,
        episodeId: String? = null
    ): SeriesEpisode? {
        val sorted = sortedEpisodes(episodes)
        if (sorted.isEmpty()) return null
        val idx = indexOfEpisode(sorted, season, episodeNum, episodeId)
        if (idx >= 0) return sorted.getOrNull(idx + 1)
        if (season == null || episodeNum == null) return sorted.first()
        return sorted.firstOrNull { ep ->
            ep.season > season || (ep.season == season && ep.episodeNum > episodeNum)
        }
    }

    /** Current episode through the end of the series (for external-player playlists). */
    fun remainingFrom(
        episodes: List<SeriesEpisode>,
        season: Int?,
        episodeNum: Int?,
        episodeId: String? = null
    ): List<SeriesEpisode> {
        val sorted = sortedEpisodes(episodes)
        if (sorted.isEmpty()) return emptyList()
        val idx = indexOfEpisode(sorted, season, episodeNum, episodeId)
        return if (idx >= 0) sorted.drop(idx) else sorted
    }

    /** Last watched episode if still in the list, else first episode. */
    fun continueEpisode(
        episodes: List<SeriesEpisode>,
        season: Int?,
        episodeNum: Int?,
        episodeId: String? = null
    ): SeriesEpisode? {
        findEpisode(episodes, season, episodeNum, episodeId)?.let { return it }
        nextEpisode(episodes, season, episodeNum, episodeId)?.let { return it }
        return sortedEpisodes(episodes).firstOrNull()
    }
}
