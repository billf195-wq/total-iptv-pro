package com.totaliptv.pro.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesPlaybackTest {

    private fun ep(season: Int, num: Int, id: String = "$season-$num"): SeriesEpisode =
        SeriesEpisode(
            id = id,
            title = "E$num",
            season = season,
            episodeNum = num,
            streamUrl = "http://example.test/$id.mp4"
        )

    @Test
    fun nextEpisodeWalksSeasonThenNextSeason() {
        val eps = listOf(ep(2, 1), ep(1, 2), ep(1, 1), ep(2, 2))
        assertEquals("1-2", SeriesPlayback.nextEpisode(eps, 1, 1)?.id)
        assertEquals("2-1", SeriesPlayback.nextEpisode(eps, 1, 2)?.id)
        assertEquals("2-2", SeriesPlayback.nextEpisode(eps, 2, 1)?.id)
        assertNull(SeriesPlayback.nextEpisode(eps, 2, 2))
    }

    @Test
    fun nextEpisodePrefersEpisodeIdWhenNumbersCollide() {
        val eps = listOf(ep(1, 0, "aaa"), ep(1, 0, "bbb"), ep(1, 0, "ccc"))
        assertEquals("bbb", SeriesPlayback.nextEpisode(eps, 1, 0, "ep-aaa")?.id)
        assertEquals("ccc", SeriesPlayback.nextEpisode(eps, 1, 0, "bbb")?.id)
        assertNull(SeriesPlayback.nextEpisode(eps, 1, 0, "ccc"))
    }

    @Test
    fun remainingFromCurrentThroughEnd() {
        val eps = listOf(ep(1, 1), ep(1, 2), ep(2, 1))
        assertEquals(listOf("1-2", "2-1"), SeriesPlayback.remainingFrom(eps, 1, 2).map { it.id })
        assertEquals(listOf("1-1", "1-2", "2-1"), SeriesPlayback.remainingFrom(eps, null, null).map { it.id })
    }

    @Test
    fun seasonFilterAndContinue() {
        val eps = listOf(ep(1, 1), ep(1, 2), ep(3, 1))
        assertEquals(listOf(1, 3), SeriesPlayback.seasonNumbers(eps))
        assertEquals(listOf("3-1"), SeriesPlayback.inSeason(eps, 3).map { it.id })
        assertEquals("1-2", SeriesPlayback.continueEpisode(eps, 1, 2)?.id)
        assertEquals("1-1", SeriesPlayback.continueEpisode(eps, null, null)?.id)
        assertEquals("3-1", SeriesPlayback.continueEpisode(eps, 3, 1, "3-1")?.id)
    }

    @Test
    fun resumeLookupMatchesSeriesKeyNotEpisodeStreamId() {
        val entries = listOf(
            ResumeStore.ResumeEntry(
                key = "series-99",
                catalogId = "series-99",
                name = "Show",
                kind = ContentKind.SERIES.name,
                seriesId = 99,
                episodeId = "555",
                season = 2,
                episodeNum = 4
            )
        )
        assertEquals(2, ResumeStore.forSeries(99, entries)?.season)
        assertEquals("555", ResumeStore.forSeries(99, entries)?.episodeId)
        assertNull(ResumeStore.forSeries(555, entries))
    }

    @Test
    fun splashIsFifteenSecondsNotCatalogHold() {
        assertEquals(15_000L, com.totaliptv.pro.desktop.ui.SplashTiming.DURATION_MS)
        assertTrue(com.totaliptv.pro.desktop.ui.SplashTiming.MAX_CATALOG_HOLD_MS > com.totaliptv.pro.desktop.ui.SplashTiming.DURATION_MS)
    }
}
