package com.totaliptv.pro.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamPlayerTest {

    @Test
    fun vlcCommandDisablesOneInstanceAndPlayAndExit() {
        val cmd = StreamPlayer.vlcCommand("/usr/bin/vlc", listOf("http://example.test/ep1.mp4"))
        assertEquals("/usr/bin/vlc", cmd.first())
        assertTrue(cmd.contains("--no-one-instance"))
        assertTrue(cmd.contains("--play-and-exit"))
        assertTrue(cmd.contains("--no-playlist-enqueue"))
        assertTrue(cmd.contains("http://example.test/ep1.mp4"))
    }

    @Test
    fun vlcPlaylistWritesM3uInsteadOfSingleUrl() {
        val urls = listOf("http://example.test/a.mp4", "http://example.test/b.mp4")
        val cmd = StreamPlayer.vlcCommand("/usr/bin/vlc", urls)
        assertTrue(cmd.last().endsWith("series-next.m3u"))
        val body = java.io.File(cmd.last()).readText()
        assertTrue(body.contains("#EXTM3U"))
        assertTrue(body.contains(urls[0]))
        assertTrue(body.contains(urls[1]))
        assertFalse(cmd.contains(urls[0]))
    }

    @Test
    fun mpvReceivesFullQueue() {
        val urls = listOf("http://example.test/a.mp4", "http://example.test/b.mp4")
        val cmd = StreamPlayer.mpvCommand("mpv", urls)
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
