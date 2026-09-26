package com.totaliptv.pro.ui.player

import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerStreamTest {

    private val hls = "http://example.test:80/live/u/p/341.m3u8"
    private val ts = "http://example.test:80/live/u/p/341.ts"
    private val vod = "http://example.test:80/movie/u/p/9.mp4"

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
    fun splitBuffersAreSmallerThanSingleLive() {
        assertTrue(PlayerStream.SPLIT_MAX_BUFFER_MS < PlayerStream.LIVE_MAX_BUFFER_MS)
        assertTrue(PlayerStream.SPLIT_MIN_BUFFER_MS <= PlayerStream.SPLIT_MAX_BUFFER_MS)
        assertTrue(PlayerStream.SPLIT_PLAYBACK_BUFFER_MS < PlayerStream.SPLIT_MIN_BUFFER_MS)
        assertTrue(PlayerStream.SPLIT_REBUFFER_MS <= PlayerStream.SPLIT_MIN_BUFFER_MS)
        assertTrue(PlayerStream.SPLIT_TARGET_BUFFER_BYTES in 1..(12 * 1024 * 1024))
    }

    @Test
    fun gameDayEntryPointsAndSplitPlayer() {
        val split = java.io.File("src/main/java/com/totaliptv/pro/ui/player/SplitPlayerActivity.kt").readText()
        assertTrue(split.contains("SPLIT_TARGET_BUFFER_BYTES"))
        assertTrue(split.contains("KEYCODE_DPAD_LEFT"))
        assertTrue(split.contains("KEYCODE_DPAD_RIGHT"))
        assertTrue(split.contains("KEYCODE_MENU"))
        assertTrue(split.contains("Sound: Left"))
        assertTrue(split.contains("Sound: Right"))
        assertTrue(split.contains("Change channel"))
        assertTrue(split.contains("GameDayChannelPicker"))
        assertTrue(split.contains("closePicker"))
        assertTrue(split.contains("Stop split"))
        assertTrue(split.contains("releaseBoth"))
        assertTrue(split.contains("handleAudioFocus= */ false"))
        assertTrue(split.contains("This side failed"))
        val home = java.io.File("src/tv/java/com/totaliptv/pro/ui/home/HomeScreen.kt").readText()
        val guide = java.io.File("src/tv/java/com/totaliptv/pro/ui/epg/EpgGuideScreen.kt").readText()
        val phone = java.io.File("src/phone/java/com/totaliptv/pro/ui/browse/PhoneBrowseScreen.kt").readText()
        val desktopLive = java.io.File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
        assertTrue(home.contains("Game Day"))
        assertTrue(guide.contains("Split"))
        assertTrue(guide.contains("GameDayPicker"))
        assertFalse(phone.contains("Game Day"))
        assertFalse(phone.contains("GameDayPicker"))
        val phoneManifest = java.io.File("src/phone/AndroidManifest.xml").readText()
        assertTrue(phoneManifest.contains("SplitPlayerActivity"))
        assertTrue(phoneManifest.contains("tools:node=\"remove\""))
        assertTrue(desktopLive.contains("Game Day"))
        val gradle = java.io.File("build.gradle.kts").readText()
        assertTrue(gradle.contains("192.168.4.33:8765"))
        assertFalse(gradle.contains("192.168.4.37"))
        assertTrue(gradle.contains("versionName = \"1.4.72\""))
        assertTrue(gradle.contains("versionName = \"1.4.49-phone\""))
        assertTrue(gradle.contains("versionCode = 84"))
        assertTrue(gradle.contains("versionCode = 61"))
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
