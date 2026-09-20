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
        assertTrue(cmd.contains("http://example.test/ep1.mp4"))
        assertFalse(cmd.contains("--ignore-config"))
        assertFalse(cmd.contains("--no-one-instance-when-started-from-file"))
        assertFalse(cmd.contains("--no-repeat"))
    }

    @Test
    fun linuxVlcPlaylistWritesM3uInsteadOfSingleUrl() {
        val urls = listOf("http://example.test/a.mp4", "http://example.test/b.mp4")
        val cmd = StreamPlayer.vlcCommand("/usr/bin/vlc", urls, windows = false)
        assertTrue(cmd.last().endsWith("series-next.m3u"))
        val body = java.io.File(cmd.last()).readText()
        assertTrue(body.contains("#EXTM3U"))
        assertTrue(body.contains("#EXTINF:-1,Total IPTV Pro"))
        assertTrue(body.contains(urls[0]))
        assertTrue(body.contains(urls[1]))
        assertFalse(cmd.contains(urls[0]))
        assertFalse(cmd.contains("--ignore-config"))
        assertTrue(StreamPlayer.treatsLaunchAsPlaylist("vlc", urls.size, windows = false))
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
    fun windowsLaunchIsNeverAPlaylistSoAppSequentialNextRuns() {
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("vlc", 12, windows = true))
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("mpv", 12, windows = true))
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("ffplay", 12, windows = false))
        assertTrue(StreamPlayer.treatsLaunchAsPlaylist("vlc", 2, windows = false))
        assertTrue(StreamPlayer.treatsLaunchAsPlaylist("mpv", 2, windows = false))
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
    fun windowsMpvLaunchesOnlyFirstUrlAndDisablesLoop() {
        val urls = listOf("http://example.test/a.mp4", "http://example.test/b.mp4")
        val win = StreamPlayer.mpvCommand("mpv.exe", urls, windows = true)
        assertTrue(win.contains("--loop-file=no"))
        assertTrue(win.contains("--loop-playlist=no"))
        assertTrue(win.contains("--keep-open=no"))
        assertEquals(urls[0], win.last())
        assertFalse(win.contains(urls[1]))
        val linux = StreamPlayer.mpvCommand("mpv", urls, windows = false)
        assertEquals(listOf("mpv", "--fullscreen", "--force-window=yes", "--title=Total IPTV Pro") + urls, linux)
    }

    @Test
    fun mpvReceivesFullQueueOnLinux() {
        val urls = listOf("http://example.test/a.mp4", "http://example.test/b.mp4")
        val cmd = StreamPlayer.mpvCommand("mpv", urls, windows = false)
        assertEquals(listOf("mpv", "--fullscreen", "--force-window=yes", "--title=Total IPTV Pro") + urls, cmd)
    }

    @Test
    fun ffplayIsSingleFileOnly() {
        val cmd = StreamPlayer.ffplayCommand("ffplay", "http://example.test/a.mp4")
        assertEquals("ffplay", cmd.first())
        assertTrue(cmd.contains("-autoexit"))
        assertEquals("http://example.test/a.mp4", cmd.last())
    }
}
