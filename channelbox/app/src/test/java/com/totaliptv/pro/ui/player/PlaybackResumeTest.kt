package com.totaliptv.pro.ui.player

import androidx.media3.common.C
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.WatchProgress
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaybackResumeTest {
    @Test
    fun waitsUntilMovieDurationIsKnown() {
        val saved = progress(positionMs = 120_000L, durationMs = 3_600_000L)
        assertIs<PlaybackResume.Decision.WaitForDuration>(
            PlaybackResume.decide(startOver = false, saved, C.TIME_UNSET)
        )
        assertIs<PlaybackResume.Decision.WaitForDuration>(
            PlaybackResume.decide(startOver = false, saved, 0L)
        )
    }

    @Test
    fun seeksInsideAMovieAndSkipsTheEnding() {
        val saved = progress(positionMs = 120_000L, durationMs = 3_600_000L)
        val seek = PlaybackResume.decide(startOver = false, saved, 3_600_000L)
        assertEquals(PlaybackResume.Decision.Seek(120_000L), seek)
        assertIs<PlaybackResume.Decision.Skip>(
            PlaybackResume.decide(startOver = false, saved, 140_000L)
        )
    }

    @Test
    fun doesNotSeekPastTheRealDurationOrNearTheStart() {
        val saved = progress(positionMs = 90_000L, durationMs = 3_600_000L)
        assertIs<PlaybackResume.Decision.Skip>(
            PlaybackResume.decide(startOver = false, saved, 60_000L)
        )
        val early = progress(positionMs = 5_000L, durationMs = 3_600_000L)
        assertIs<PlaybackResume.Decision.Skip>(
            PlaybackResume.decide(startOver = false, early, 3_600_000L)
        )
    }

    @Test
    fun startOverIgnoresSavedPosition() {
        val saved = progress(positionMs = 120_000L, durationMs = 3_600_000L)
        assertIs<PlaybackResume.Decision.StartOver>(
            PlaybackResume.decide(startOver = true, saved, 3_600_000L)
        )
    }

    @Test
    fun playerSurvivesPlaybackFailureInsteadOfReleasingInsideTheCallback() {
        val src = File("src/main/java/com/totaliptv/pro/ui/player/PlayerActivity.kt").readText()
        val errorFn = src.substringAfter("override fun onPlayerError")
            .substringBefore("override fun onPlaybackStateChanged")
        assertFalse(errorFn.contains("initPlayer()"))
        assertTrue(errorFn.contains("handlePlayerError"))
        assertTrue(src.contains("scheduleRebuild"))
        assertTrue(src.contains("PlaybackResume.decide"))
        assertTrue(src.contains("omitForcedMime"))
        assertTrue(src.contains("showPlaybackFailure"))
        val app = File("src/main/java/com/totaliptv/pro/TotalIptvProApp.kt").readText()
        assertTrue(app.contains("CrashLog.install"))
        val tv = File("src/tv/java/com/totaliptv/pro/ui/settings/SettingsScreen.kt").readText()
        val phone = File("src/phone/java/com/totaliptv/pro/ui/settings/PhoneSettingsScreen.kt").readText()
        val desktop = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
        assertTrue(tv.contains("Last crash"))
        assertTrue(phone.contains("Last crash"))
        assertTrue(desktop.contains("Last crash"))
        assertTrue(tv.contains("Debug log"))
        assertTrue(phone.contains("Debug log"))
        assertTrue(desktop.contains("Debug log"))
    }

    private fun progress(positionMs: Long, durationMs: Long) = WatchProgress(
        id = "vod-9",
        positionMs = positionMs,
        durationMs = durationMs,
        title = "Movie",
        kind = ContentKind.VOD,
        streamUrl = "http://example/movie/u/p/9.mp4"
    )
}
