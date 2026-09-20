package com.totaliptv.pro.dvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DvrScheduleTest {
    @Test
    fun leadInAndStop() {
        val start = 10_000L
        val end = 20_000L
        assertFalse(DvrSchedule.shouldStart(start - 20_000L, start))
        assertTrue(DvrSchedule.shouldStart(start - 15_000L, start))
        assertTrue(DvrSchedule.isDue(start, start, end))
        assertFalse(DvrSchedule.isDue(end, start, end))
        assertTrue(DvrSchedule.shouldStop(end, end))
        assertEquals(5_000L, DvrSchedule.remainingMs(15_000L, 20_000L))
    }

    @Test
    fun scheduledDuration() {
        val item = ScheduledRecording(
            id = "s",
            channelName = "HBO",
            title = "Movie",
            streamUrl = "http://h/live/u/p/1.m3u8",
            startMs = 0L,
            endMs = 3_600_000L
        )
        assertEquals(3_600_000L, item.durationMs())
    }
}
