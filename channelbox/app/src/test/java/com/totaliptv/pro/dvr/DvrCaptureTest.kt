package com.totaliptv.pro.dvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DvrCaptureTest {
    @Test
    fun detectsHls() {
        assertTrue(DvrCapture.isHlsUrl("http://host/live/u/p/12.m3u8"))
        assertFalse(DvrCapture.isHlsUrl("http://host/live/u/p/12.ts"))
    }

    @Test
    fun parsesSegmentsAndMaster() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            a.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val parsed = DvrCapture.parsePlaylistUris(media, "http://host/live/u/p/12.m3u8")
        assertFalse(parsed.master)
        assertEquals(6, parsed.targetDurationSec)
        assertEquals("http://host/live/u/p/a.ts", parsed.uris[0])
        assertTrue(parsed.ended)

        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1
            child.m3u8
        """.trimIndent()
        val m = DvrCapture.parsePlaylistUris(master, "http://host/live/u/p/master.m3u8")
        assertTrue(m.master)
        assertEquals("http://host/live/u/p/child.m3u8", m.uris[0])
    }
}
