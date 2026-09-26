package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.EpgProgram
import com.totaliptv.pro.data.model.MediaItem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuideBulkCacheTest {
    @Test
    fun xmltvUrlHasNoLanHostAndRoundTrips() {
        val url = GuideBulkCache.xmltvUrl("http://example.test", "user", "p a")
        assertTrue(url.contains("example.test/xmltv.php"))
        assertTrue(url.contains("username=user"))
        assertFalse(url.contains("192.168"))
        assertFalse(url.contains(" "))

        val file = File.createTempFile("guide", ".tsv")
        val programs = listOf(EpgProgram("News", startMs = 10L, endMs = 20L, channelStreamId = 7))
        GuideBulkCache.write(file, mapOf(7 to programs))
        assertTrue(GuideBulkCache.isFresh(file, file.lastModified() + 1000L))
        assertFalse(GuideBulkCache.isFresh(file, file.lastModified() + GuideBulkCache.TTL_MS + 5L))
        val read = GuideBulkCache.read(file)
        assertEquals("News", read[7]?.single()?.title)
        assertFalse(file.readText().contains("user"))
    }

    @Test
    fun indexMatchesStreamIdThenName() {
        val channels = listOf(
            MediaItem(
                id = "live-1",
                name = "CNN",
                streamUrl = "http://example.test/live/u/p/1.ts",
                categoryId = "c",
                kind = ContentKind.LIVE,
                xtreamStreamId = 1,
                epgChannelId = "cnn.us"
            )
        )
        val programs = listOf(EpgProgram("Hour", startMs = 1L, endMs = 2L))
        val indexed = GuideBulkCache.indexByStream(mapOf("cnn.us" to programs), channels)
        assertEquals("Hour", indexed[1]?.single()?.title)
    }
}
