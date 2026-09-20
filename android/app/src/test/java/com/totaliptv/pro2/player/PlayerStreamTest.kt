package com.totaliptv.pro2.player

import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerStreamTest {

    @Test
    fun streamUserAgentIsVlcNotExoPlayer() {
        assertTrue(PlayerStream.STREAM_USER_AGENT.contains("VLC"))
        assertTrue(PlayerStream.STREAM_USER_AGENT.contains("LibVLC"))
        assertFalse(PlayerStream.STREAM_USER_AGENT.contains("ExoPlayer", ignoreCase = true))
        assertFalse(PlayerStream.STREAM_USER_AGENT.contains("Media3", ignoreCase = true))
    }

    @Test
    fun liveTargetSitsInsideShortXtreamWindow() {
        assertTrue(PlayerStream.LIVE_TARGET_OFFSET_MS < 12_000L)
        assertTrue(PlayerStream.LIVE_MAX_OFFSET_MS <= 20_000L)
    }

    @Test
    fun mimeAndTsFallbackForXtreamLive() {
        val hls = "http://hudv.net:80/live/u/p/341.m3u8"
        assertTrue(PlayerStream.isHlsUrl(hls))
        assertEquals(MimeTypes.APPLICATION_M3U8, PlayerStream.mimeForUrl(hls))
        assertEquals("http://hudv.net:80/live/u/p/341.ts", PlayerStream.tsFallbackUrl(hls))
        assertNull(PlayerStream.tsFallbackUrl("http://hudv.net:80/movie/u/p/9.mp4"))
    }

    @Test
    fun playerWiresHttpFactoryAndMime() {
        val src = java.io.File("src/main/java/com/totaliptv/pro2/player/PlayerActivity.kt").readText()
        assertTrue(src.contains("PlayerStream.STREAM_USER_AGENT"))
        assertTrue(src.contains("setAllowCrossProtocolRedirects(true)"))
        assertTrue(src.contains("mimeForUrl"))
        assertTrue(src.contains("ERROR_CODE_BEHIND_LIVE_WINDOW"))
    }
}
