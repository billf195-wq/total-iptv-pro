package com.totaliptv.pro.desktop.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeriesNextBannerLayoutTest {

    @Test
    fun windowGrowsToContentAndStopsAtTheScreen() {
        assertEquals(180, SeriesNextBannerLayout.targetWindowPx(180, 800))
        assertEquals(800, SeriesNextBannerLayout.targetWindowPx(2000, 800))
        assertTrue(SeriesNextBannerLayout.shouldResize(currentWindowPx = 160, measuredContentPx = 180, maxWindowPx = 800))
        assertFalse(SeriesNextBannerLayout.shouldResize(currentWindowPx = 180, measuredContentPx = 180, maxWindowPx = 800))
        assertFalse(SeriesNextBannerLayout.shouldResize(currentWindowPx = 800, measuredContentPx = 2000, maxWindowPx = 800))
        assertTrue(SeriesNextBannerLayout.shouldResize(currentWindowPx = 400, measuredContentPx = 180, maxWindowPx = 800))
        assertFalse(SeriesNextBannerLayout.shouldResize(currentWindowPx = 186, measuredContentPx = 180, maxWindowPx = 800))
        assertFalse(SeriesNextBannerLayout.shouldResize(currentWindowPx = 200, measuredContentPx = 0, maxWindowPx = 800))
    }

    @Test
    fun bannerTextIsSmallerAndHeightFollowsContent() {
        assertTrue(SeriesNextBannerLayout.LABEL_SP <= 11)
        assertTrue(SeriesNextBannerLayout.SERIES_SP <= 13)
        assertTrue(SeriesNextBannerLayout.BUTTON_SP <= 12)
        assertTrue(SeriesNextBannerLayout.HINT_SP <= 11)
        val overlay = File("src/main/kotlin/com/totaliptv/pro/desktop/ui/SeriesNextOverlay.kt").readText()
        assertTrue(overlay.contains("reportContentHeight"))
        assertTrue(overlay.contains("SeriesNextBannerLayout.BUTTON_SP"))
        assertTrue(overlay.contains("verticalScroll"))
        assertTrue(overlay.contains("wrapContentHeight"))
        assertFalse(overlay.contains("height(48.dp)"), "fixed button height clips large font scale")
        assertFalse(overlay.contains("196 * scale"), "fixed window height clips the bottom hint")
        assertFalse(overlay.contains("fontSize = 16.sp"))
    }
}
