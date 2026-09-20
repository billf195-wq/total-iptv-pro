package com.totaliptv.pro.desktop.data

import com.totaliptv.pro.desktop.player.PlaybackAdvance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SeriesAdvanceTest {

    private fun ep(season: Int, num: Int, id: String = "$season-$num"): SeriesEpisode =
        SeriesEpisode(
            id = id,
            title = "E$num",
            season = season,
            episodeNum = num,
            streamUrl = "http://cdn.example.test/series/user/pass/$id.mp4"
        )

    @Test
    fun s1e1NextAfterPlayingAndPlanAreS1e2WithDifferentUrl() {
        val e1 = ep(1, 1)
        val e2 = ep(1, 2)
        val eps = listOf(e1, e2)
        val next = SeriesPlayback.nextAfterPlaying(eps, 1, 1, e1.id, e1.streamUrl)
        assertEquals(e2.id, next?.id)
        assertEquals(e2.streamUrl, next?.streamUrl)
        assertTrue(next!!.streamUrl != e1.streamUrl)

        val plan = SeriesLaunch.plan(e1.toMediaItem("Show", 9), eps, "Show", 9)
        assertEquals(listOf(e1.streamUrl), plan.urls)
        assertEquals(e2.id, plan.nextEpisode?.id)
        assertEquals(e2.streamUrl, plan.nextEpisode?.streamUrl)
        assertTrue(plan.nextEpisode!!.streamUrl != plan.start.streamUrl)
        assertTrue(plan.nextEpisode!!.streamUrl != e1.streamUrl)
    }

    @Test
    fun naturalEndAfterRealPlayAdvancesToDifferentUrl() {
        val e1 = ep(1, 1)
        val e2 = ep(1, 2)
        val plan = SeriesLaunch.plan(e1.toMediaItem("Show", 3), listOf(e1, e2), "Show", 3)
        val outcome = SeriesAdvance.afterNaturalEnd(
            start = plan.start,
            plan = plan,
            episodes = listOf(e1, e2),
            seriesName = "Show",
            seriesId = 3,
            durationMs = 45_000,
            exitCode = 0
        )
        val play = assertIs<SeriesAdvance.Outcome.PlayNext>(outcome)
        assertEquals(PlaybackAdvance.REASON_AUTO_ADVANCE, play.reason)
        assertEquals(e2.id, play.item.id.removePrefix("ep-"))
        assertEquals(e2.streamUrl, play.item.streamUrl)
        assertTrue(play.item.streamUrl != plan.start.streamUrl)
    }

    @Test
    fun shortPlayStopsWithLoggedReason() {
        val e1 = ep(1, 1)
        val e2 = ep(1, 2)
        val plan = SeriesLaunch.plan(e1.toMediaItem("Show", 3), listOf(e1, e2), "Show", 3)
        val outcome = SeriesAdvance.afterNaturalEnd(
            start = plan.start,
            plan = plan,
            episodes = listOf(e1, e2),
            seriesName = "Show",
            seriesId = 3,
            durationMs = 8_400,
            exitCode = 0
        )
        val stop = assertIs<SeriesAdvance.Outcome.Stop>(outcome)
        assertEquals(PlaybackAdvance.REASON_SKIPPED_SHORT_PLAY, stop.reason)
    }

    @Test
    fun userSkipBypassesShortPlayGuardAndChangesUrl() {
        val e1 = ep(1, 1)
        val e2 = ep(1, 2)
        val outcome = SeriesAdvance.afterSkip(
            current = e1,
            plannedNext = e2,
            episodes = listOf(e1, e2),
            seriesName = "Show",
            seriesId = 3
        )
        val play = assertIs<SeriesAdvance.Outcome.PlayNext>(outcome)
        assertEquals("skip", play.reason)
        assertEquals(e2.streamUrl, play.item.streamUrl)
        assertTrue(play.item.streamUrl != e1.streamUrl)
    }

    @Test
    fun sameUrlNextStopsInsteadOfRelaunch() {
        val e1 = ep(1, 1, "same")
        val twin = e1.copy(id = "same-again", episodeNum = 2, streamUrl = e1.streamUrl)
        val start = e1.toMediaItem("Show", 1)
        val outcome = SeriesAdvance.resolveDistinctNext(
            start = start,
            plannedNext = twin,
            episodes = listOf(e1, twin),
            seriesName = "Show",
            seriesId = 1,
            sameReason = PlaybackAdvance.REASON_SAME_URL,
            playReason = PlaybackAdvance.REASON_AUTO_ADVANCE
        )
        val stop = assertIs<SeriesAdvance.Outcome.Stop>(outcome)
        assertEquals(PlaybackAdvance.REASON_SAME_URL, stop.reason)
    }

    @Test
    fun appRootWiresShortPlayGuardAndSameUrlStop() {
        val root = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/AppRoot.kt").readText()
        assertTrue(root.contains("SeriesAdvance.afterNaturalEnd"))
        assertTrue(root.contains("PlaybackAdvance.REASON_SAME_URL"))
        assertTrue(root.contains("SeriesAdvance.afterSkip"))
    }
}
