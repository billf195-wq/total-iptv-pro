package com.totaliptv.pro.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun seasonFilterAndContinue() {
        val eps = listOf(ep(1, 1), ep(1, 2), ep(3, 1))
        assertEquals(listOf(1, 3), SeriesPlayback.seasonNumbers(eps))
        assertEquals(listOf("3-1"), SeriesPlayback.inSeason(eps, 3).map { it.id })
        assertEquals("1-2", SeriesPlayback.continueEpisode(eps, 1, 2)?.id)
        assertEquals("1-1", SeriesPlayback.continueEpisode(eps, null, null)?.id)
    }
}
