package com.totaliptv.pro.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesLaunchTest {

    private fun ep(season: Int, num: Int, id: String = "$season-$num"): SeriesEpisode =
        SeriesEpisode(
            id = id,
            title = "E$num",
            season = season,
            episodeNum = num,
            streamUrl = "http://cdn.example.test/series/user/pass/$id.mp4"
        )

    @Test
    fun windowsPlanUsesOnlyClickedEpisodeUrl() {
        val eps = listOf(ep(1, 1), ep(1, 2), ep(2, 3))
        val item = eps[2].toMediaItem("Show", 44)
        val plan = SeriesLaunch.plan(item, eps, "Show", 44, windowsSingleUrl = true)
        assertEquals(listOf(eps[2].streamUrl), plan.urls)
        assertEquals("2-3", plan.currentEpisode?.id)
        assertNull(plan.nextEpisode)
        assertEquals(item.streamUrl, plan.start.streamUrl)
    }

    @Test
    fun linuxPlanKeepsRemainingEpisodeUrls() {
        val eps = listOf(ep(1, 1), ep(1, 2), ep(2, 1))
        val item = eps[1].toMediaItem("Show", 7)
        val plan = SeriesLaunch.plan(item, eps, "Show", 7, windowsSingleUrl = false)
        assertEquals(listOf(eps[1].streamUrl, eps[2].streamUrl), plan.urls)
        assertEquals("1-2", plan.currentEpisode?.id)
        assertEquals("2-1", plan.nextEpisode?.id)
    }

    @Test
    fun identityMissFallsBackToStreamUrlNotS1E1() {
        val eps = listOf(ep(1, 1), ep(1, 2), ep(2, 1))
        val clicked = MediaItem(
            id = "ep-resume",
            name = "Show — S2E1",
            streamUrl = eps[2].streamUrl,
            categoryId = null,
            kind = ContentKind.SERIES,
            playable = true,
            parentSeriesId = 9,
            parentSeriesName = "Show",
            season = null,
            episodeNum = null
        )
        val windows = SeriesLaunch.plan(clicked, eps, "Show", 9, windowsSingleUrl = true)
        assertEquals(listOf(eps[2].streamUrl), windows.urls)
        assertEquals("2-1", windows.currentEpisode?.id)
        val linux = SeriesLaunch.plan(clicked, eps, "Show", 9, windowsSingleUrl = false)
        assertEquals(listOf(eps[2].streamUrl), linux.urls)
        assertTrue(linux.urls.none { it == eps[0].streamUrl })
    }

    @Test
    fun unknownUrlDoesNotLaunchFromS1E1() {
        val eps = listOf(ep(1, 1), ep(1, 2))
        val clicked = MediaItem(
            id = "ep-unknown",
            name = "Clicked",
            streamUrl = "http://cdn.example.test/series/user/pass/unique-click.mp4",
            categoryId = null,
            kind = ContentKind.SERIES,
            playable = true,
            parentSeriesId = 3,
            parentSeriesName = "Show"
        )
        val plan = SeriesLaunch.plan(clicked, eps, "Show", 3, windowsSingleUrl = true)
        assertEquals(listOf(clicked.streamUrl), plan.urls)
        assertNull(plan.currentEpisode)
    }

    @Test
    fun nextEpisodeFromCurrentIsSxxEPlusOne() {
        val eps = listOf(ep(1, 4), ep(1, 5), ep(1, 6))
        val item = eps[0].toMediaItem("Show", 1)
        val plan = SeriesLaunch.plan(item, eps, "Show", 1, windowsSingleUrl = true)
        assertEquals("1-5", plan.nextEpisode?.id)
        assertTrue(plan.nextEpisode!!.streamUrl != plan.start.streamUrl)
        assertEquals("http://cdn.example.test/series/user/pass/1-5.mp4", plan.nextEpisode!!.streamUrl)
    }

    @Test
    fun skipAfterIdentityMissUsesUrlNeighborNotS1E1() {
        val eps = listOf(ep(1, 1), ep(1, 2), ep(1, 3))
        val clicked = MediaItem(
            id = "ep-resume",
            name = "Show — S1E2",
            streamUrl = eps[1].streamUrl,
            categoryId = null,
            kind = ContentKind.SERIES,
            playable = true,
            parentSeriesId = 4,
            parentSeriesName = "Show"
        )
        val plan = SeriesLaunch.plan(clicked, eps, "Show", 4, windowsSingleUrl = true)
        assertEquals(listOf(eps[1].streamUrl), plan.urls)
        assertEquals("1-3", plan.nextEpisode?.id)
        assertTrue(plan.nextEpisode!!.streamUrl != plan.urls.first())
    }
}
