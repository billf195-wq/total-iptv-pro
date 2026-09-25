package com.totaliptv.pro.data.m3u

import com.totaliptv.pro.data.model.ContentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class M3uParserSeriesTest {

    @Test
    fun worldSeriesAndNascarStayOnLiveTv() {
        assertFalse(M3uKind.isSeries("http://example.test/live/world.ts", "Sports"))
        assertFalse(M3uKind.isSeries("http://example.test/live/nascar.m3u8", "NASCAR Cup Series"))
        assertTrue(M3uKind.isSeries("http://example.test/series/12/34.mp4", "Drama"))
        assertTrue(M3uKind.isSeries("http://example.test/vod/1.mp4", "Series"))
        assertTrue(M3uKind.isSeries("http://example.test/vod/2.mp4", "Entertainment", "series"))

        val playlist = """
            #EXTM3U url-tvg="http://example.test/guide.xml"
            #EXTINF:-1 tvg-id="fox" group-title="Sports",World Series
            http://example.test/live/world.ts
            #EXTINF:-1 tvg-id="nbc" group-title="Motorsport",NASCAR Cup Series
            http://example.test/live/nascar.ts
            #EXTINF:-1 group-title="Series",Night Shift
            http://cdn.example.test/series/9/1.mkv
            #EXTINF:-1 tvg-type="series" group-title="Drama",Hidden
            http://cdn.example.test/files/hidden.mp4
        """.trimIndent()

        val parsed = M3uParser.parse(playlist)
        assertEquals("http://example.test/guide.xml", parsed.xmltvUrl)
        val kinds = parsed.items.associate { it.name to it.kind }
        assertEquals(ContentKind.LIVE, kinds.getValue("World Series"))
        assertEquals(ContentKind.LIVE, kinds.getValue("NASCAR Cup Series"))
        assertEquals(ContentKind.SERIES, kinds.getValue("Night Shift"))
        assertEquals(ContentKind.SERIES, kinds.getValue("Hidden"))
    }
}
