package com.totaliptv.pro.ui.player

import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerStreamTest {

    private val hls = "http://hudv.net:80/live/u/p/341.m3u8"
    private val ts = "http://hudv.net:80/live/u/p/341.ts"
    private val vod = "http://hudv.net:80/movie/u/p/9.mp4"

    @Test
    fun streamUserAgentIsVlcNotExoPlayer() {
        assertTrue(PlayerStream.STREAM_USER_AGENT.contains("VLC"))
        assertFalse(PlayerStream.STREAM_USER_AGENT.contains("ExoPlayer", ignoreCase = true))
    }

    @Test
    fun livePrefersMpegTsLikeVodProgressive() {
        assertEquals(ts, PlayerStream.preferredExoUrl(hls, live = true))
        assertEquals(ts, PlayerStream.preferredExoUrl(ts, live = true))
        assertEquals(vod, PlayerStream.preferredExoUrl(vod, live = false))
        assertEquals(hls, PlayerStream.alternateLiveUrl(ts, hls))
        assertEquals(ts, PlayerStream.alternateLiveUrl(hls, hls))
        assertNull(PlayerStream.alternateLiveUrl(vod, vod))
        assertEquals(MimeTypes.VIDEO_MP2T, PlayerStream.mimeForUrl(ts))
        assertEquals(MimeTypes.APPLICATION_M3U8, PlayerStream.mimeForUrl(hls))
        assertEquals(MimeTypes.VIDEO_MP4, PlayerStream.mimeForUrl(vod))
    }

    @Test
    fun liveBuffersFitShortXtreamWindow() {
        assertTrue(PlayerStream.LIVE_MIN_BUFFER_MS < 12_000)
        assertTrue(PlayerStream.LIVE_PLAYBACK_BUFFER_MS < 2_000)
        assertTrue(PlayerStream.LIVE_MAX_BUFFER_MS <= 25_000)
        assertTrue(PlayerStream.LIVE_STUCK_BUFFER_MS <= 10_000L)
    }

    @Test
    fun guidePlayUsesPlayerActivityNotVlcIntent() {
        val guide = java.io.File("src/tv/java/com/totaliptv/pro/ui/epg/EpgGuideScreen.kt").readText()
        assertTrue(guide.contains("latestOnPlay(playable)"))
        assertTrue(guide.contains("repository.playableFrom(channel)"))
        assertFalse(guide.contains("org.videolan.vlc"))
        assertFalse(guide.contains("ACTION_VIEW"))
        val main = java.io.File("src/tv/java/com/totaliptv/pro/MainActivity.kt").readText()
        assertTrue(main.contains("Intent(this, PlayerActivity::class.java)"))
        assertTrue(main.contains("PlayerActivity.EXTRA_KIND"))
        assertFalse(main.contains("org.videolan.vlc"))
    }

    @Test
    fun playerStartsLiveOnTsAndWatchesStuckBuffer() {
        val src = java.io.File("src/main/java/com/totaliptv/pro/ui/player/PlayerActivity.kt").readText()
        assertTrue(src.contains("PlayerStream.preferredExoUrl"))
        assertTrue(src.contains("tryAlternateLiveUrl"))
        assertTrue(src.contains("watchLiveBufferStuck"))
        assertFalse(src.contains("setTargetOffsetMs(35_000)"))
        assertFalse(src.contains("minBufferMs = 30_000"))
        assertTrue(src.contains("Always start built-in ExoPlayer"))
    }
}
