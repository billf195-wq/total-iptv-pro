package com.totaliptv.pro.desktop.data

/**
 * How a series play should be handed to the external player.
 *
 * Windows: one episode URL. VLC’s playlist Next cannot advance a one-item list,
 * so in-app / hotkey Next is the supported path.
 * Linux: remaining episodes (M3U / argv queue) so VLC Next / EOF still work.
 */
data class SeriesLaunchPlan(
    val start: MediaItem,
    val urls: List<String>,
    val allEpisodes: List<SeriesEpisode>,
    val seriesName: String,
    val seriesId: Int?,
    val currentEpisode: SeriesEpisode?,
    val nextEpisode: SeriesEpisode?
)

object SeriesLaunch {
    fun single(item: MediaItem): SeriesLaunchPlan = SeriesLaunchPlan(
        start = item,
        urls = listOfNotNull(item.streamUrl.trim().takeIf { it.isNotBlank() }),
        allEpisodes = emptyList(),
        seriesName = item.parentSeriesName.orEmpty(),
        seriesId = item.parentSeriesId,
        currentEpisode = null,
        nextEpisode = null
    )

    fun plan(
        item: MediaItem,
        episodes: List<SeriesEpisode>,
        seriesName: String,
        seriesId: Int?,
        windowsSingleUrl: Boolean
    ): SeriesLaunchPlan {
        val current = resolveCurrent(episodes, item)
        val start = current?.toMediaItem(seriesName, seriesId) ?: item
        val next = current?.let { ep ->
            SeriesPlayback.nextEpisode(episodes, ep.season, ep.episodeNum, ep.id)
        } ?: SeriesPlayback.nextEpisode(episodes, start.season, start.episodeNum, start.id)
        val urls = launchUrls(
            start = start,
            episodes = episodes,
            current = current,
            windowsSingleUrl = windowsSingleUrl
        )
        return SeriesLaunchPlan(
            start = start,
            urls = urls,
            allEpisodes = SeriesPlayback.sortedEpisodes(episodes),
            seriesName = seriesName,
            seriesId = seriesId,
            currentEpisode = current,
            nextEpisode = next
        )
    }

    /**
     * Prefer id / SxxExx, then exact stream URL. Never silently substitute S01E01
     * when the clicked row is a different episode whose index lookup missed.
     */
    fun resolveCurrent(episodes: List<SeriesEpisode>, item: MediaItem): SeriesEpisode? {
        SeriesPlayback.findEpisode(episodes, item.season, item.episodeNum, item.id)?.let { return it }
        val url = item.streamUrl.trim()
        if (url.isNotBlank()) {
            SeriesPlayback.sortedEpisodes(episodes).firstOrNull { it.streamUrl.trim() == url }?.let { return it }
        }
        return null
    }

    fun launchUrls(
        start: MediaItem,
        episodes: List<SeriesEpisode>,
        current: SeriesEpisode?,
        windowsSingleUrl: Boolean
    ): List<String> {
        val startUrl = start.streamUrl.trim()
        if (windowsSingleUrl) {
            return listOfNotNull(startUrl.takeIf { it.isNotBlank() })
        }
        if (current != null) {
            val remaining = SeriesPlayback.remainingFrom(
                episodes,
                current.season,
                current.episodeNum,
                current.id
            ).map { it.streamUrl.trim() }.filter { it.isNotBlank() }
            if (remaining.isNotEmpty()) return remaining
        }
        return listOfNotNull(startUrl.takeIf { it.isNotBlank() })
    }
}
