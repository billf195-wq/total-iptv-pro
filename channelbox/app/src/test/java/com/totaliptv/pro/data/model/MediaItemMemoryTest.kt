package com.totaliptv.pro.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MediaItemMemoryTest {
    private fun item(plot: String? = null, trailer: String? = null) = MediaItem(
        id = "v1",
        name = "Title",
        streamUrl = "playable://v1",
        categoryId = "vod",
        kind = ContentKind.VOD,
        plot = plot,
        youtubeTrailer = trailer
    )

    @Test
    fun stripsPlotAndTrailerButKeepsStreamUrl() {
        val src = item(plot = "A long plot " + "x".repeat(200), trailer = "abc123")
        val out = src.withoutPlotAndTrailer()
        assertNull(out.plot)
        assertNull(out.youtubeTrailer)
        assertEquals("playable://v1", out.streamUrl)
        assertEquals("Title", out.name)
    }

    @Test
    fun listHelperKeepsIdentityWhenAlreadyClean() {
        val list = listOf(item())
        assertSame(list, list.withoutPlotAndTrailer())
        val dirty = listOf(item(plot = "p"))
        val cleaned = dirty.withoutPlotAndTrailer()
        assertTrue(cleaned !== dirty)
        assertNull(cleaned[0].plot)
    }
}
