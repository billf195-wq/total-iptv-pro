package com.totaliptv.pro.ui.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpNextTimingTest {
    @Test
    fun bannerShowsOnlyInTheLastThirtySecondsOfASeriesEpisode() {
        val end = 600_000L
        assertTrue(UpNextTiming.shouldShow(true, end - 30_000L, end))
        assertTrue(UpNextTiming.shouldShow(true, end - 1L, end))
        assertFalse(UpNextTiming.shouldShow(true, end - 30_001L, end))
        assertFalse(UpNextTiming.shouldShow(true, end, end))
        assertFalse(UpNextTiming.shouldShow(false, end - 1_000L, end))
        assertFalse(UpNextTiming.shouldShow(true, 1_000L, 0L))
    }
}
