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
}
