package com.totaliptv.pro.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDebugLogTest {

    @Test
    fun redactStreamUrlKeepsHostAndPathTailWithoutCredentials() {
        val raw = "http://bigboybill.example:8080/series/secret-user/secret-pass/5551212.mkv"
        val redacted = PlaybackDebugLog.redactStreamUrl(raw)
        assertTrue(redacted.startsWith("bigboybill.example:8080/"), redacted)
        assertTrue(redacted.endsWith("/5551212.mkv"), redacted)
        assertFalse(redacted.contains("secret-user"))
        assertFalse(redacted.contains("secret-pass"))
    }

    @Test
    fun formatLineIncludesEpisodeIdentityAndPlayer() {
        val line = PlaybackDebugLog.formatLine(
            episodeId = "5551212",
            season = 2,
            episodeNum = 4,
            streamUrl = "http://host.test/series/u/p/5551212.mp4",
            playerBinary = """C:\Program Files\VideoLAN\VLC\vlc.exe""",
            windows = true,
            playlist = false,
            reason = "skip"
        )
        assertTrue(line.contains("episodeId=5551212"), line)
        assertTrue(line.contains("S2E4"), line)
        assertTrue(line.contains("reason=skip"), line)
        assertTrue(line.contains("player=C:\\Program Files\\VideoLAN\\VLC\\vlc.exe"), line)
        assertTrue(line.contains("windows=true"), line)
        assertTrue(line.contains("playlist=false"), line)
        assertTrue(line.contains("url=host.test/.../5551212.mp4"), line)
        assertEquals(PlaybackDebugLog.FILE_NAME, "playback-debug.log")
    }

    @Test
    fun formatLineIncludesShortPlayDurationMs() {
        val line = PlaybackDebugLog.formatLine(
            episodeId = "555",
            season = 2,
            episodeNum = 3,
            streamUrl = "http://host.test/series/u/p/555.mp4",
            playerBinary = "vlc",
            windows = true,
            playlist = false,
            reason = "auto-advance-skipped-short-play",
            durationMs = 8400,
            exitCode = 0
        )
        assertTrue(line.contains("reason=auto-advance-skipped-short-play"), line)
        assertTrue(line.contains("durationMs=8400"), line)
        assertTrue(line.contains("exitCode=0"), line)
    }

    @Test
    fun formatLineIncludesEpisodeCountAndIndexOnSkipNoNext() {
        val line = PlaybackDebugLog.formatLine(
            episodeId = "909664",
            season = 3,
            episodeNum = 10,
            streamUrl = "http://host.test/series/u/p/909664.mp4",
            playerBinary = "-",
            windows = true,
            playlist = false,
            reason = "skip-no-next",
            episodeCount = 40,
            episodeIndex = 39
        )
        assertTrue(line.contains("reason=skip-no-next"), line)
        assertTrue(line.contains("episodeId=909664"), line)
        assertTrue(line.contains("S3E10"), line)
        assertTrue(line.contains("eps=40"), line)
        assertTrue(line.contains("idx=39"), line)
    }

    @Test
    fun formatStackKeepsTheExceptionTypeAndFrames() {
        val error = IllegalStateException("comparison contract")
        val text = PlaybackDebugLog.formatStack("tmdb-ratings-fetch", error)
        assertTrue(text.contains("uncaught thread=tmdb-ratings-fetch"), text)
        assertTrue(text.contains("IllegalStateException"), text)
        assertTrue(text.contains("comparison contract"), text)
        assertTrue(text.contains("formatStackKeepsTheExceptionTypeAndFrames"), text)
        assertTrue(text.contains("\n"), text)
        assertTrue(text.length <= PlaybackDebugLog.MAX_STACK_CHARS)
    }

    @Test
    fun uncaughtHandlerWritesTheStackAndChainsThePreviousHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val recorded = mutableListOf<String>()
        val chained = mutableListOf<String>()
        try {
            UncaughtLog.writeStack = { name, error ->
                recorded += PlaybackDebugLog.formatStack(name, error)
            }
            Thread.setDefaultUncaughtExceptionHandler { _, error ->
                chained += error.javaClass.name
            }
            UncaughtLog.install()
            UncaughtLog.install()
            val handler = Thread.getDefaultUncaughtExceptionHandler()
            handler?.uncaughtException(Thread.currentThread(), IllegalStateException("comparison contract"))
            assertEquals(1, recorded.size)
            assertTrue(recorded[0].contains("IllegalStateException"), recorded[0])
            assertTrue(recorded[0].contains("comparison contract"), recorded[0])
            assertTrue(recorded[0].contains("\n"), recorded[0])
            assertEquals(listOf(IllegalStateException::class.java.name), chained)
        } finally {
            UncaughtLog.resetForTests()
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
