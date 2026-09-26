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
     * Next and last-episode chrome both use it. A Swing timer alone can miss
     * a beat under fullscreen VLC; every overlay sync consults this too.
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
        if (key != null && dismissedKey == key) return false
        val shown = firstShownAtMs ?: return true
        return !isAutoDismissed(shown, nowMs)
    }

    /**
     * Player-exit is separate from the clock: once this episode's player has
     * been seen running, a dead player must not leave the banner up.
     */
    fun shouldShow(
        mode: Mode,
        firstShownAtMs: Long?,
        nowMs: Long,
        dismissedKey: String?,
        key: String?,
        sawPlayer: Boolean,
        playerRunning: Boolean
    ): Boolean {
        if (!overlayStillVisible(mode, firstShownAtMs, nowMs, dismissedKey, key)) return false
        if (sawPlayer && !playerRunning) return false
        return true
    }

    fun logLine(action: String, mode: Mode, reason: String, episodeId: String?, monitorX: Int? = null, monitorY: Int? = null): String {
        return buildString {
            append("banner ")
            append(action)
            append(" mode=")
            append(mode.name)
            append(" reason=")
            append(reason.ifBlank { "-" })
            append(" episodeId=")
            append(episodeId?.trim()?.ifBlank { null } ?: "-")
            if (monitorX != null && monitorY != null) {
                append(" monitor=")
                append(monitorX)
                append(',')
                append(monitorY)
            }
        }
    }
}
