package com.totaliptv.pro.desktop.dvr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DvrScheduleTest {

    @Test
    fun startsWithLeadInBeforeEpgStart() {
        val start = 1_000_000L
        assertFalse(DvrSchedule.shouldStart(start - 20_000L, start))
        assertTrue(DvrSchedule.shouldStart(start - 15_000L, start))
        assertTrue(DvrSchedule.shouldStart(start + 1L, start))
    }

    @Test
    fun stopsAtOrAfterEnd() {
        val end = 2_000_000L
        assertFalse(DvrSchedule.shouldStop(end - 1L, end))
        assertTrue(DvrSchedule.shouldStop(end, end))
        assertFalse(DvrSchedule.shouldStop(end, null))
    }

    @Test
    fun remainingIsNonNegative() {
        assertEquals(5_000L, DvrSchedule.remainingMs(1_000L, 6_000L))
        assertEquals(0L, DvrSchedule.remainingMs(9_000L, 6_000L))
        assertNull(DvrSchedule.remainingMs(1_000L, null))
    }

    @Test
    fun dueOnlyInsideWindow() {
        val start = 100_000L
        val end = 200_000L
        assertFalse(DvrSchedule.isDue(0L, start, end))
        assertFalse(DvrSchedule.isDue(start - DvrSchedule.LEAD_MS - 1L, start, end))
        assertTrue(DvrSchedule.isDue(start, start, end))
        assertFalse(DvrSchedule.isDue(end, start, end))
        assertTrue(DvrSchedule.isExpired(end, end))
    }

    @Test
    fun scheduledDurationFromStartEnd() {
        val item = ScheduledRecording(
            id = "s1",
            channelName = "CNN",
            title = "News",
            streamUrl = "http://host/live/u/p/1.m3u8",
            startMs = 1000L,
            endMs = 61_000L
        )
        assertEquals(60_000L, item.durationMs())
    }
}
