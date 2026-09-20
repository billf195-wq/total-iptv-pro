package com.totaliptv.pro.desktop.player

import java.io.File
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
        assertFalse(cmd.contains("--no-one-instance-when-started-from-file"))
        assertFalse(cmd.contains("--recursive=expand"))
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
        assertFalse(cmd.contains("--recursive=expand"))
    }

    @Test
    fun windowsVlcQueuePassesEpisodeUrlsAsSeparateArgs() {
        val urls = listOf("http://example.test/s01e01.mkv", "http://example.test/s01e02.mkv")
        val cmd = StreamPlayer.vlcCommand("""C:\Program Files\VideoLAN\VLC\vlc.exe""", urls, windows = true)
        assertTrue(cmd.contains("--no-one-instance"))
        assertTrue(cmd.contains("--play-and-exit"))
        assertTrue(cmd.contains("--no-playlist-enqueue"))
        assertTrue(cmd.contains("--no-one-instance-when-started-from-file"))
        assertTrue(cmd.contains("--no-started-from-file"))
        assertTrue(cmd.contains("--no-repeat"))
        assertTrue(cmd.contains("--no-loop"))
        assertTrue(cmd.contains("--recursive=expand"))
        assertEquals(urls[0], cmd[cmd.indexOf("--meta-title=Total IPTV Pro") + 1])
        assertEquals(urls[1], cmd.last())
        assertTrue(cmd.contains(urls[0]))
        assertTrue(cmd.contains(urls[1]))
        assertFalse(cmd.any { it.endsWith(".m3u") || it.contains(".m3u") })
    }

    @Test
    fun windowsVlcFallsBackToFileUriM3uWhenCommandLineWouldOverflow() {
        val urls = (1..80).map { i ->
            "http://example.test/very/long/xtream/series/user/password/token/" +
                "episode-$i-" + "x".repeat(400) + ".mkv"
        }
        val cmd = StreamPlayer.vlcCommand("vlc.exe", urls, windows = true)
        val last = cmd.last()
        assertTrue(last.startsWith("file:"), last)
        assertTrue(last.contains("total-iptv-pro-series-next.m3u"), last)
        assertFalse(last.contains('\\'), last)
        assertTrue(cmd.contains("--recursive=expand"))
        val file = File(System.getProperty("java.io.tmpdir"), "total-iptv-pro-series-next.m3u")
        assertTrue(file.isFile)
        val body = file.readText()
        assertTrue(body.startsWith("#EXTM3U\r\n"))
        assertTrue(body.contains("#EXTINF:0,Episode 1\r\n"))
        assertTrue(body.contains("#EXTINF:0,Episode 2\r\n"))
        assertTrue(body.contains(urls.first()))
        assertTrue(body.contains(urls.last()))
        assertFalse(body.contains("#EXTINF:-1"))
    }

    @Test
    fun fileToVlcMrlUsesFileSchemeAndEncodesSpaces() {
        val f = File(System.getProperty("java.io.tmpdir"), "bill foster playlist.m3u")
        val mrl = StreamPlayer.fileToVlcMrl(f)
        assertTrue(mrl.startsWith("file:"))
        assertTrue(mrl.contains("%20"), mrl)
        assertFalse(mrl.contains('\\'), mrl)
        assertTrue(mrl.startsWith("file://"))
    }

    @Test
    fun windowsMpvDisablesLoopSoNextDoesNotReplay() {
        val urls = listOf("http://example.test/a.mp4", "http://example.test/b.mp4")
        val win = StreamPlayer.mpvCommand("mpv.exe", urls, windows = true)
        assertTrue(win.contains("--loop-file=no"))
        assertTrue(win.contains("--loop-playlist=no"))
        assertEquals(urls, win.takeLast(2))
        val linux = StreamPlayer.mpvCommand("mpv", urls, windows = false)
        assertEquals(listOf("mpv", "--fullscreen", "--force-window=yes", "--title=Total IPTV Pro") + urls, linux)
    }

    @Test
    fun mpvReceivesFullQueue() {
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
