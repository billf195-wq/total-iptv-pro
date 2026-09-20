package com.totaliptv.pro.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LastEpisodeBannerTest {

    private fun ep(season: Int, num: Int, id: String = "$season-$num"): SeriesEpisode =
        SeriesEpisode(
            id = id,
            title = "E$num",
            season = season,
            episodeNum = num,
            streamUrl = "http://host/series/u/p/$id.mp4"
        )

    @Test
    fun unknownPositionIsNotLastEpisode() {
        val eps = listOf(ep(1, 1), ep(1, 2), ep(1, 3))
        assertFalse(LastEpisodeBanner.isKnownLastEpisode(eps, 9, 9, "missing", "http://nope"))
        assertEquals(
            LastEpisodeBanner.Mode.HIDDEN,
            LastEpisodeBanner.overlayMode(eps, 9, 9, "missing", "http://nope")
        )
        assertEquals(LastEpisodeBanner.Mode.HIDDEN, LastEpisodeBanner.overlayMode(emptyList(), 1, 1, "1-1"))
    }

    @Test
    fun finaleShowsBrieflyThenMustNotStay() {
        val eps = listOf(ep(1, 1), ep(1, 2))
        assertTrue(LastEpisodeBanner.isKnownLastEpisode(eps, 1, 2, "1-2"))
        assertTrue(LastEpisodeBanner.shouldShowBriefly(eps, 1, 2, "1-2"))
        assertFalse(LastEpisodeBanner.shouldStayVisible())
        assertEquals(4_000L, LastEpisodeBanner.AUTO_DISMISS_MS)
        assertEquals(
            LastEpisodeBanner.Mode.LAST_BRIEF,
            LastEpisodeBanner.overlayMode(eps, 1, 2, "1-2", eps[1].streamUrl)
        )
    }

    @Test
    fun midSeriesShowsNextNotLastBanner() {
        val eps = listOf(ep(1, 1), ep(1, 2), ep(1, 3))
        assertFalse(LastEpisodeBanner.isKnownLastEpisode(eps, 1, 1, "1-1"))
        assertEquals(
            LastEpisodeBanner.Mode.NEXT,
            LastEpisodeBanner.overlayMode(eps, 1, 1, "1-1", eps[0].streamUrl)
        )
    }
}
