package com.totaliptv.pro.desktop.player

import com.totaliptv.pro.desktop.data.SeriesPlayback

/**
 * Decide whether a natural player exit should start SxxE(n+1).
 *
 * 1.2.8 treated every [StreamPlayer.waitForExit] as EOF. Failed / one-instance
 * VLC exits in 5–15s then AppRoot auto-advanced (Windows episode churn).
 * 1.2.9 requires a minimum play time unless the user pressed Next/Skip.
 */
object PlaybackAdvance {
    const val MIN_NATURAL_PLAY_MS: Long = 20_000L

    const val REASON_AUTO_ADVANCE: String = "auto-advance"
    const val REASON_SKIPPED_SHORT_PLAY: String = "auto-advance-skipped-short-play"
    const val REASON_SKIPPED_EXIT_CODE: String = "auto-advance-skipped-exit-code"
    const val REASON_SAME_URL: String = "auto-advance-same-url"
    const val REASON_SKIP_SAME_URL: String = "skip-same-url"
    const val REASON_NO_NEXT: String = "auto-advance-no-next"

    fun shouldAutoAdvance(
        durationMs: Long,
        userRequestedNext: Boolean = false,
        exitCode: Int? = 0
    ): Boolean {
        if (userRequestedNext) return true
        if (exitCode != null && exitCode != 0) return false
        return durationMs >= MIN_NATURAL_PLAY_MS
    }

    fun skipReason(durationMs: Long, exitCode: Int?): String {
        if (exitCode != null && exitCode != 0) return REASON_SKIPPED_EXIT_CODE
        if (durationMs < MIN_NATURAL_PLAY_MS) return REASON_SKIPPED_SHORT_PLAY
        return REASON_AUTO_ADVANCE
    }

    /** True when next would relaunch the same episode id or stream URL. */
    fun isSameLaunch(
        currentId: String?,
        currentUrl: String?,
        nextId: String?,
        nextUrl: String?
    ): Boolean {
        val curId = SeriesPlayback.normalizeEpisodeId(currentId)
        val nxtId = SeriesPlayback.normalizeEpisodeId(nextId)
        if (!curId.isNullOrBlank() && curId == nxtId) return true
        val curUrl = currentUrl?.trim().orEmpty()
        val nxtUrl = nextUrl?.trim().orEmpty()
        return curUrl.isNotBlank() && curUrl == nxtUrl
    }
}
