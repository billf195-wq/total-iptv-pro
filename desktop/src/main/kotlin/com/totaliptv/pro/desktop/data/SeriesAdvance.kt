package com.totaliptv.pro.desktop.data

import com.totaliptv.pro.desktop.player.PlaybackAdvance

/**
 * Next-episode decision after a player exit or in-app Skip.
 * Linux and Windows both launch one URL; this is the only sequential-next path.
 */
object SeriesAdvance {
    sealed class Outcome {
        data class PlayNext(val item: MediaItem, val reason: String) : Outcome()
        data class Stop(val reason: String) : Outcome()
    }

    fun afterNaturalEnd(
        start: MediaItem,
        plan: SeriesLaunchPlan,
        episodes: List<SeriesEpisode>,
        seriesName: String,
        seriesId: Int?,
        durationMs: Long,
        exitCode: Int?,
        userRequestedNext: Boolean = false,
        positionMs: Long? = null,
        lengthMs: Long? = null,
        reachedEof: Boolean = false
    ): Outcome {
        val live = start.kind == ContentKind.LIVE
        if (!userRequestedNext && !PlaybackAdvance.shouldAutoAdvance(
                durationMs, false, exitCode, live, positionMs, lengthMs, reachedEof
            )
        ) {
            return Outcome.Stop(
                PlaybackAdvance.skipReason(durationMs, exitCode, live, positionMs, lengthMs, reachedEof)
            )
        }
        return resolveDistinctNext(
            start = start,
            plannedNext = plan.nextEpisode,
            episodes = episodes.ifEmpty { plan.allEpisodes },
            seriesName = seriesName.ifBlank { plan.seriesName },
            seriesId = seriesId ?: plan.seriesId,
            sameReason = PlaybackAdvance.REASON_SAME_URL,
            playReason = if (userRequestedNext) "skip" else PlaybackAdvance.REASON_AUTO_ADVANCE
        )
    }

    fun afterSkip(
        current: SeriesEpisode?,
        plannedNext: SeriesEpisode?,
        episodes: List<SeriesEpisode>,
        seriesName: String,
        seriesId: Int?
    ): Outcome {
        val start = current?.toMediaItem(seriesName, seriesId)
            ?: return Outcome.Stop("skip-no-next")
        return resolveDistinctNext(
            start = start,
            plannedNext = plannedNext,
            episodes = episodes,
            seriesName = seriesName,
            seriesId = seriesId,
            sameReason = PlaybackAdvance.REASON_SKIP_SAME_URL,
            playReason = "skip",
            noNextReason = "skip-no-next"
        )
    }

    fun resolveDistinctNext(
        start: MediaItem,
        plannedNext: SeriesEpisode?,
        episodes: List<SeriesEpisode>,
        seriesName: String,
        seriesId: Int?,
        sameReason: String,
        playReason: String,
        noNextReason: String = PlaybackAdvance.REASON_NO_NEXT
    ): Outcome {
        val nextEp = plannedNext
            ?: SeriesPlayback.nextAfterPlaying(
                episodes,
                start.season,
                start.episodeNum,
                start.id,
                start.streamUrl
            )
        if (nextEp == null || nextEp.streamUrl.isBlank()) {
            return Outcome.Stop(noNextReason)
        }
        val nextItem = nextEp.toMediaItem(seriesName, seriesId)
        if (PlaybackAdvance.isSameLaunch(start.id, start.streamUrl, nextItem.id, nextItem.streamUrl)) {
            return Outcome.Stop(sameReason)
        }
        return Outcome.PlayNext(nextItem, playReason)
    }
}
