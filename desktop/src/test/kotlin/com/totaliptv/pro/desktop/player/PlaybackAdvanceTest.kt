package com.totaliptv.pro.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackAdvanceTest {

    @Test
    fun shortPlayDoesNotAutoAdvanceUnlessUserSkip() {
        assertFalse(PlaybackAdvance.shouldAutoAdvance(durationMs = 5_000, userRequestedNext = false, exitCode = 0))
        assertFalse(PlaybackAdvance.shouldAutoAdvance(durationMs = 15_000, userRequestedNext = false, exitCode = 0))
        assertFalse(PlaybackAdvance.shouldAutoAdvance(durationMs = 19_999, userRequestedNext = false, exitCode = 0))
        assertFalse(PlaybackAdvance.shouldAutoAdvance(durationMs = 76_000, userRequestedNext = false, exitCode = 0))
        assertTrue(
            PlaybackAdvance.shouldAutoAdvance(
                durationMs = 76_000,
                userRequestedNext = false,
                exitCode = 0,
                positionMs = 2_400_000,
                lengthMs = 2_450_000
            )
        )
        assertTrue(
            PlaybackAdvance.shouldAutoAdvance(
                durationMs = 76_000,
                userRequestedNext = false,
                exitCode = 0,
                reachedEof = true
            )
        )
        assertTrue(PlaybackAdvance.shouldAutoAdvance(durationMs = 3_000, userRequestedNext = true, exitCode = 0))
    }

    @Test
    fun nonZeroExitCodeIsFailure() {
        assertFalse(PlaybackAdvance.shouldAutoAdvance(durationMs = 60_000, userRequestedNext = false, exitCode = 1))
        assertTrue(PlaybackAdvance.shouldAutoAdvance(durationMs = 60_000, userRequestedNext = true, exitCode = 1))
        assertEquals(
            PlaybackAdvance.REASON_SKIPPED_EXIT_CODE,
            PlaybackAdvance.skipReason(durationMs = 60_000, exitCode = 1)
        )
    }

    @Test
    fun shortPlayReasonIncludesDurationContract() {
        assertEquals(20_000L, PlaybackAdvance.MIN_NATURAL_PLAY_MS)
        assertEquals(
            PlaybackAdvance.REASON_SKIPPED_SHORT_PLAY,
            PlaybackAdvance.skipReason(durationMs = 8_000, exitCode = 0)
        )
        assertEquals(
            PlaybackAdvance.REASON_USER_STOP,
            PlaybackAdvance.skipReason(durationMs = 76_000, exitCode = 0, positionMs = 76_000, lengthMs = 2_400_000)
        )
        assertEquals(
            PlaybackAdvance.REASON_AUTO_ADVANCE,
            PlaybackAdvance.skipReason(durationMs = 45_000, exitCode = 0, positionMs = 40_000, lengthMs = 50_000)
        )
        assertEquals(90_000L, PlaybackAdvance.NEAR_END_MS)
    }

    @Test
    fun liveNeverAutoAdvancesEvenAfterLongPlay() {
        assertFalse(
            PlaybackAdvance.shouldAutoAdvance(
                durationMs = 120_000,
                userRequestedNext = false,
                exitCode = 0,
                live = true
            )
        )
        assertTrue(
            PlaybackAdvance.shouldAutoAdvance(
                durationMs = 45_000,
                userRequestedNext = false,
                exitCode = 0,
                live = false,
                positionMs = 40_000,
                lengthMs = 50_000
            )
        )
        assertEquals(
            PlaybackAdvance.REASON_SKIPPED_LIVE,
            PlaybackAdvance.skipReason(durationMs = 8_000, exitCode = 0, live = true)
        )
        assertEquals(
            PlaybackAdvance.REASON_SKIPPED_SHORT_PLAY,
            PlaybackAdvance.skipReason(durationMs = 8_000, exitCode = 0, live = false)
        )
    }

    @Test
    fun sameLaunchDetectsEpisodeIdOrUrl() {
        assertTrue(
            PlaybackAdvance.isSameLaunch(
                currentId = "ep-101",
                currentUrl = "http://cdn.example.test/a.mp4",
                nextId = "101",
                nextUrl = "http://cdn.example.test/b.mp4"
            )
        )
        assertTrue(
            PlaybackAdvance.isSameLaunch(
                currentId = "ep-1",
                currentUrl = "http://cdn.example.test/s01e01.mp4",
                nextId = "ep-2",
                nextUrl = "http://cdn.example.test/s01e01.mp4"
            )
        )
        assertFalse(
            PlaybackAdvance.isSameLaunch(
                currentId = "ep-1",
                currentUrl = "http://cdn.example.test/s01e01.mp4",
                nextId = "ep-2",
                nextUrl = "http://cdn.example.test/s01e02.mp4"
            )
        )
    }
}
