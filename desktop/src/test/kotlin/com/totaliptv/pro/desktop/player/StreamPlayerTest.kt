package com.totaliptv.pro.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamPlayerTest {

    @Test
    fun linuxVlcCommandDisablesOneInstanceAndPlayAndExit() {
        val cmd = StreamPlayer.vlcCommand("/usr/bin/vlc", listOf("http://example.test/ep1.mp4"), windows = false)
        assertEquals("/usr/bin/vlc", cmd.first())
        assertTrue(cmd.contains("--no-one-instance"))
        assertTrue(cmd.contains("--play-and-exit"))
        assertTrue(cmd.contains("--no-playlist-enqueue"))
        assertTrue(cmd.contains("--no-repeat"))
        assertTrue(cmd.contains("--no-loop"))
        assertTrue(cmd.contains("http://example.test/ep1.mp4"))
        assertFalse(cmd.contains("--ignore-config"))
        assertFalse(cmd.contains("--no-one-instance-when-started-from-file"))
        assertEquals("http://example.test/ep1.mp4", cmd.last())
    }

    @Test
    fun linuxVlcLaunchesOnlyTheFirstEpisodeUrl() {
        val urls = listOf("http://example.test/a.mp4", "http://example.test/b.mp4")
        val cmd = StreamPlayer.vlcCommand("/usr/bin/vlc", urls, windows = false)
        assertEquals(urls[0], cmd.last())
        assertFalse(cmd.contains(urls[1]))
        assertFalse(cmd.any { it.contains(".m3u") })
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("vlc", urls.size, windows = false))
    }

    @Test
    fun windowsVlcLaunchesOnlyTheFirstEpisodeUrl() {
        val urls = listOf("http://example.test/s01e01.mkv", "http://example.test/s01e02.mkv")
        val cmd = StreamPlayer.vlcCommand("""C:\Program Files\VideoLAN\VLC\vlc.exe""", urls, windows = true)
        assertEquals("--ignore-config", cmd[1])
        assertTrue(cmd.contains("--no-one-instance"))
        assertTrue(cmd.contains("--play-and-exit"))
        assertTrue(cmd.contains("--no-playlist-enqueue"))
        assertTrue(cmd.contains("--no-one-instance-when-started-from-file"))
        assertTrue(cmd.contains("--no-started-from-file"))
        assertTrue(cmd.contains("--no-repeat"))
        assertTrue(cmd.contains("--no-loop"))
        assertEquals(urls[0], cmd.last())
        assertFalse(cmd.contains(urls[1]))
        assertFalse(cmd.any { it.contains(".m3u") })
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("vlc", urls.size, windows = true))
    }

    @Test
    fun launchIsNeverAPlaylistSoAppSequentialNextRuns() {
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("vlc", 12, windows = true))
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("mpv", 12, windows = true))
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("ffplay", 12, windows = false))
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("vlc", 2, windows = false))
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("mpv", 2, windows = false))
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("vlc", 39, windows = false))
    }

    @Test
    fun windowsQuitKillsVlcMpvAndFfplayTrees() {
        val names = StreamPlayer.windowsQuitImageNames("""C:\Program Files\VideoLAN\VLC\vlc.exe""")
        assertTrue(names.contains("vlc.exe"))
        assertTrue(names.contains("mpv.exe"))
        assertTrue(names.contains("ffplay.exe"))
        assertEquals(
            listOf("taskkill.exe", "/F", "/T", "/IM", "vlc.exe"),
            StreamPlayer.windowsKillCommand("vlc.exe")
        )
    }

    @Test
    fun waitForExitRecordsDurationAndExitCode() {
        StreamPlayer.markLaunch(nowMs = 1_000L)
        StreamPlayer.markExit(exitCode = 0, nowMs = 9_500L)
        assertEquals(0, StreamPlayer.lastExitCode)
        assertEquals(8_500L, StreamPlayer.lastPlaybackDurationMs)
        StreamPlayer.markLaunch(nowMs = 20_000L)
        StreamPlayer.markExit(exitCode = 1, nowMs = 21_200L)
        assertEquals(1, StreamPlayer.lastExitCode)
        assertEquals(1_200L, StreamPlayer.lastPlaybackDurationMs)
    }

    @Test
    fun windowsKillTargetsVlcImageEvenFromProgramFilesPath() {
        val names = StreamPlayer.windowsKillImageNames("""C:\Program Files\VideoLAN\VLC\vlc.exe""")
        assertEquals(listOf("vlc.exe"), names)
        assertEquals(
            listOf("taskkill.exe", "/F", "/T", "/IM", "vlc.exe"),
            StreamPlayer.windowsKillCommand(names.first())
        )
        assertEquals(listOf("vlc.exe"), StreamPlayer.windowsKillImageNames("vlc"))
        assertEquals(listOf("mpv.exe"), StreamPlayer.windowsKillImageNames("mpv.exe"))
    }

    @Test
    fun mpvLaunchesOnlyFirstUrlAndDisablesLoopOnLinuxAndWindows() {
        val urls = listOf("http://example.test/a.mp4", "http://example.test/b.mp4")
        val win = StreamPlayer.mpvCommand("mpv.exe", urls, windows = true)
        assertTrue(win.contains("--loop-file=no"))
        assertTrue(win.contains("--loop-playlist=no"))
        assertTrue(win.contains("--keep-open=no"))
        assertEquals(urls[0], win.last())
        assertFalse(win.contains(urls[1]))
        val linux = StreamPlayer.mpvCommand("mpv", urls, windows = false)
        assertTrue(linux.contains("--loop-file=no"))
        assertTrue(linux.contains("--loop-playlist=no"))
        assertTrue(linux.contains("--keep-open=no"))
        assertEquals(urls[0], linux.last())
        assertFalse(linux.contains(urls[1]))
    }

    @Test
    fun ffplayIsSingleFileOnly() {
        val cmd = StreamPlayer.ffplayCommand("ffplay", "http://example.test/a.mp4")
        assertEquals("ffplay", cmd.first())
        assertTrue(cmd.contains("-autoexit"))
        assertEquals("http://example.test/a.mp4", cmd.last())
    }

    @Test
    fun liveUrlDetectsXtreamHlsAndSkipsVodContainers() {
        assertTrue(StreamPlayer.isLiveStreamUrl("http://hudv.net:80/live/user/pass/3.m3u8"))
        assertTrue(StreamPlayer.isLiveStreamUrl("http://hudv.net/live/u/p/67.ts"))
        assertTrue(StreamPlayer.isLiveStreamUrl("http://cdn.example.test/playlist.m3u8"))
        assertFalse(StreamPlayer.isLiveStreamUrl("http://hudv.net:80/movie/user/pass/825824.mp4"))
        assertFalse(StreamPlayer.isLiveStreamUrl("http://hudv.net:80/series/user/pass/627490.mkv"))
        assertFalse(StreamPlayer.isLiveStreamUrl("http://hudv.net:80/movie/user/pass/99.m3u8"))
    }

    @Test
    fun windowsLiveVlcOmitsPlayAndExitAndKeepsVodGuard() {
        val liveUrl = "http://hudv.net:80/live/user/pass/341.m3u8"
        val vodUrl = "http://hudv.net:80/series/user/pass/825824.mp4"
        val vlc = """C:\Program Files\VideoLAN\VLC\vlc.exe"""
        val live = StreamPlayer.vlcCommand(vlc, listOf(liveUrl), windows = true, live = true)
        assertFalse(live.contains("--play-and-exit"), live.toString())
        assertTrue(live.contains("--network-caching=3000"), live.toString())
        assertTrue(live.contains("--live-caching=3000"), live.toString())
        assertTrue(live.contains("--http-reconnect"), live.toString())
        assertTrue(live.contains("--ignore-config"))
        assertEquals(liveUrl, live.last())
        val vod = StreamPlayer.vlcCommand(vlc, listOf(vodUrl), windows = true, live = false)
        assertTrue(vod.contains("--play-and-exit"), vod.toString())
        assertFalse(vod.contains("--http-reconnect"))
        assertEquals(vodUrl, vod.last())
    }

    @Test
    fun linuxLiveVlcAlsoOmitsPlayAndExit() {
        val live = StreamPlayer.vlcCommand(
            "/usr/bin/vlc",
            listOf("http://hudv.net/live/u/p/3.m3u8"),
            windows = false,
            live = true
        )
        assertFalse(live.contains("--play-and-exit"))
        assertTrue(live.contains("--fullscreen"))
        val vod = StreamPlayer.vlcCommand(
            "/usr/bin/vlc",
            listOf("http://hudv.net/series/u/p/1.mkv"),
            windows = false,
            live = false
        )
        assertTrue(vod.contains("--play-and-exit"))
    }

    @Test
    fun liveMpvKeepsWindowOpenAndFfplaySkipsAutoexit() {
        val liveUrl = "http://hudv.net/live/u/p/84.m3u8"
        val mpv = StreamPlayer.mpvCommand("mpv.exe", listOf(liveUrl), windows = true, live = true)
        assertTrue(mpv.contains("--keep-open=yes"))
        assertFalse(mpv.contains("--keep-open=no"))
        val vod = StreamPlayer.mpvCommand("mpv", listOf("http://ex.test/a.mp4"), windows = false, live = false)
        assertTrue(vod.contains("--keep-open=no"))
        val ffLive = StreamPlayer.ffplayCommand("ffplay", liveUrl, live = true)
        assertFalse(ffLive.contains("-autoexit"))
        val ffVod = StreamPlayer.ffplayCommand("ffplay", "http://ex.test/a.mp4", live = false)
        assertTrue(ffVod.contains("-autoexit"))
    }
}
