package com.totaliptv.pro.desktop.data

/**
 * Last-episode chrome must not sit on screen for the whole playback.
 * Show it only when the playing episode is actually the finale, then dismiss.
 */
object LastEpisodeBanner {
    const val AUTO_DISMISS_MS: Long = 4_000L

    fun isKnownLastEpisode(
        episodes: List<SeriesEpisode>,
        season: Int?,
        episodeNum: Int?,
        episodeId: String? = null,
        streamUrl: String? = null
    ): Boolean {
        val sorted = SeriesPlayback.sortedEpisodes(episodes)
        if (sorted.isEmpty()) return false
        var idx = SeriesPlayback.indexOfEpisode(sorted, season, episodeNum, episodeId)
        if (idx < 0) {
            val url = streamUrl?.trim().orEmpty()
            if (url.isNotBlank()) {
                idx = sorted.indexOfFirst { it.streamUrl.trim() == url }
            }
        }
        return idx >= 0 && idx == sorted.lastIndex
    }

    /** Persistent overlay / title bar is wrong even when it is the finale. */
    fun shouldStayVisible(): Boolean = false

    fun shouldShowBriefly(
        episodes: List<SeriesEpisode>,
        season: Int?,
        episodeNum: Int?,
        episodeId: String? = null,
        streamUrl: String? = null
    ): Boolean = isKnownLastEpisode(episodes, season, episodeNum, episodeId, streamUrl)

    fun overlayMode(
        episodes: List<SeriesEpisode>,
        season: Int?,
        episodeNum: Int?,
        episodeId: String? = null,
        streamUrl: String? = null
    ): Mode {
        if (episodes.isEmpty()) return Mode.HIDDEN
        val next = SeriesPlayback.nextAfterPlaying(episodes, season, episodeNum, episodeId, streamUrl)
        if (next != null) return Mode.NEXT
        return if (shouldShowBriefly(episodes, season, episodeNum, episodeId, streamUrl)) {
            Mode.LAST_BRIEF
        } else {
            Mode.HIDDEN
        }
    }

    enum class Mode { NEXT, LAST_BRIEF, HIDDEN }

    /**
     * Clock-based dismiss used by Windows and Linux (same Compose host).
     * A Swing timer alone can miss a beat under Windows VLC fullscreen;
     * every overlay sync consults this so the banner cannot sit for the show.
     */
    fun isAutoDismissed(shownAtMs: Long, nowMs: Long, dismissMs: Long = AUTO_DISMISS_MS): Boolean =
        nowMs - shownAtMs >= dismissMs

    fun overlayStillVisible(
        mode: Mode,
        firstShownAtMs: Long?,
        nowMs: Long,
        dismissedKey: String? = null,
        key: String? = null
    ): Boolean {
        if (mode == Mode.HIDDEN) return false
        if (mode == Mode.NEXT) return true
        if (key != null && dismissedKey == key) return false
        val shown = firstShownAtMs ?: return true
        return !isAutoDismissed(shown, nowMs)
    }
}
