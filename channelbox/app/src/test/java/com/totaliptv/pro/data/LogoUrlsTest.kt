package com.totaliptv.pro.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogoUrlsTest {
    @Test
    fun relativeAndProtocolRelativeIconsBecomeAbsolute() {
        assertEquals(
            "http://example.test/logo/x.png",
            LogoUrls.absolute("http://example.test", "/logo/x.png")
        )
        assertEquals("https://cdn.example.test/a.png", LogoUrls.absolute(null, "//cdn.example.test/a.png"))
        assertEquals("https://cdn.example.test/a.png", LogoUrls.absolute("http://example.test", "https://cdn.example.test/a.png"))
        assertNull(LogoUrls.absolute("http://example.test", " "))
        assertEquals(
            "http://example.test/logo/x.png",
            LogoUrls.forPlayback("http://example.test/live/u/p/1.m3u8", "/logo/x.png")
        )
    }
}
