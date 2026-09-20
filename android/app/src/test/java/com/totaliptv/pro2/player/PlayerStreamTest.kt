package com.totaliptv.pro2.player

import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerStreamTest {

    private val hls = "http://hudv.net:80/live/u/p/341.m3u8"
    private val ts = "http://hudv.net:80/live/u/p/341.ts"

    @Test
    fun streamUserAgentIsVlcNotExoPlayer() {
        assertTrue(PlayerStream.STREAM_USER_AGENT.contains("VLC"))
        assertFalse(PlayerStream.STREAM_USER_AGENT.contains("ExoPlayer", ignoreCase = true))
    }

    @Test
    fun livePrefersMpegTs() {
        assertEquals(ts, PlayerStream.preferredExoUrl(hls, live = true))
        assertEquals(hls, PlayerStream.alternateLiveUrl(ts, hls))
        assertEquals(MimeTypes.VIDEO_MP2T, PlayerStream.mimeForUrl(ts))
    }

    @Test
    fun playerWiresPreferredExoUrl() {
        val src = java.io.File("src/main/java/com/totaliptv/pro2/player/PlayerActivity.kt").readText()
        assertTrue(src.contains("PlayerStream.preferredExoUrl"))
        assertTrue(src.contains("alternateLiveUrl"))
        assertTrue(src.contains("setAllowCrossProtocolRedirects(true)"))
    }
}
