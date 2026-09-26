package com.totaliptv.pro.dvr

import kotlin.test.Test
import kotlin.test.assertEquals

class DvrCaptureUrlTest {
    @Test
    fun livePlaylistRecordsAsMpegTs() {
        assertEquals(
            "http://example.test/live/u/p/1.ts",
            DvrCapture.recordUrl("http://example.test/live/u/p/1.m3u8")
        )
        assertEquals(
            "http://example.test/movie/u/p/9.mp4",
            DvrCapture.recordUrl("http://example.test/movie/u/p/9.mp4")
        )
    }
}
