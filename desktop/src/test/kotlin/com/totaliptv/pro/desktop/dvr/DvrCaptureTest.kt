package com.totaliptv.pro.desktop.dvr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Path

class DvrCaptureTest {

    @Test
    fun ffmpegCopiesMpegTsAndOptionalDuration() {
        val out = Path.of("/tmp/rec.ts")
        val cmd = DvrCapture.ffmpegCommand("ffmpeg", "http://host/live/u/p/1.m3u8", out, 3600)
        assertEquals("ffmpeg", cmd.first())
        assertTrue(cmd.contains("-c"))
        assertTrue(cmd.contains("copy"))
        assertTrue(cmd.contains("-f"))
        assertTrue(cmd.contains("mpegts"))
        assertTrue(cmd.contains("-t"))
        assertTrue(cmd.contains("3600"))
        assertEquals(out.toString(), cmd.last())
    }

    @Test
    fun ffmpegOmitsDurationWhenOpenEnded() {
        val cmd = DvrCapture.ffmpegCommand("ffmpeg", "http://x/1.ts", Path.of("a.ts"), null)
        assertFalse(cmd.contains("-t"))
    }

    @Test
    fun vlcWritesTsFileAndQuits() {
        val cmd = DvrCapture.vlcCommand(
            "vlc",
            "http://host/live/u/p/1.m3u8",
            Path.of("/tmp/out.ts"),
            120,
            windows = false
        )
        assertTrue(cmd.any { it.contains("sout=") && it.contains("out.ts") })
        assertTrue(cmd.contains("--run-time=120"))
        assertTrue(cmd.contains("vlc://quit"))
    }

    @Test
    fun liveUrlsDetected() {
        assertTrue(DvrCapture.isHlsUrl("http://host/live/u/p/12.m3u8"))
        assertTrue(DvrCapture.isHlsUrl("http://host/live/u/p/12.m3u"))
        assertFalse(DvrCapture.isHlsUrl("http://host/live/u/p/12.ts"))
    }

    @Test
    fun playlistParserPicksMediaSegmentsAndMaster() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4.0,
            seg1.ts
            #EXTINF:4.0,
            http://cdn/seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val parsed = DvrCapture.parsePlaylistUris(media, "http://host/live/u/p/12.m3u8")
        assertFalse(parsed.master)
        assertEquals(4, parsed.targetDurationSec)
        assertTrue(parsed.ended)
        assertEquals("http://host/live/u/p/seg1.ts", parsed.uris[0])
        assertEquals("http://cdn/seg2.ts", parsed.uris[1])

        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
            chunklist.m3u8
        """.trimIndent()
        val m = DvrCapture.parsePlaylistUris(master, "http://host/live/u/p/master.m3u8")
        assertTrue(m.master)
        assertEquals("http://host/live/u/p/chunklist.m3u8", m.uris[0])
    }

    @Test
    fun enginePrefersFfmpegThenVlcThenHls() {
        assertEquals(DvrCapture.Engine.Kind.FFMPEG, DvrCapture.detectEngine(ffmpegExists = true, vlcBinary = "vlc").kind)
        assertEquals(DvrCapture.Engine.Kind.VLC, DvrCapture.detectEngine(ffmpegExists = false, vlcBinary = "/usr/bin/vlc").kind)
        assertEquals(DvrCapture.Engine.Kind.HLS, DvrCapture.detectEngine(ffmpegExists = false, vlcBinary = null).kind)
    }
}
