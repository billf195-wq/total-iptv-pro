package com.totaliptv.pro.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepAndAutoPlayTest {
    @Test
    fun sleepChoicesMatchTheSpec() {
        val vod = SleepTimer.choices(onDemand = true, seriesEpisode = false).map { it.label }
        assertEquals(listOf("Off", "15 minutes", "30 minutes", "60 minutes", "90 minutes", "End of this movie"), vod)
        val ep = SleepTimer.choices(onDemand = true, seriesEpisode = true).map { it.label }
        assertEquals("End of this episode", ep.last())
        val live = SleepTimer.choices(onDemand = false, seriesEpisode = false).map { it.label }
        assertEquals(5, live.size)
    }

    @Test
    fun sleepWarnsInTheLastMinuteOnly() {
        assertFalse(SleepTimer.shouldWarn(61_000L))
        assertTrue(SleepTimer.shouldWarn(60_000L))
        assertTrue(SleepTimer.shouldWarn(1L))
        assertFalse(SleepTimer.shouldWarn(0L))
        assertEquals("Playback will stop in 45s.", SleepTimer.warningMessage(44_100L))
        assertEquals("Sleep 30m", SleepTimer.buttonLabel(30 * 60_000L, false))
        assertEquals("Sleep 1m", SleepTimer.buttonLabel(5_000L, false))
        assertEquals("Sleep: end", SleepTimer.buttonLabel(null, true))
        assertEquals("Sleep", SleepTimer.buttonLabel(null, false))
    }

    @Test
    fun autoPlayCountdownCarriesFromBannerIntoTheEndDialog() {
        assertEquals(10, UpNextTiming.AUTO_PLAY_SECONDS)
        assertEquals(40, UpNextTiming.autoPlaySecondsLeft(70_000L, 100_000L))
        assertEquals(10, UpNextTiming.autoPlaySecondsLeft(100_000L, 100_000L))
        assertEquals("Up next: S1 E2  ·  plays in 25s", UpNextTiming.bannerText("S1 E2", true, 25))
        // Toggle off keeps the original banner text.
        assertEquals("Up next: S1 E2", UpNextTiming.bannerText("S1 E2", false, 25))
        assertEquals("Playing next in 7s\n\nS1 E2", UpNextTiming.countdownMessage("S1 E2", 7))
    }
}
