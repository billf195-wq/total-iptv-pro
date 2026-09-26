package com.totaliptv.pro.desktop.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvContentThemeTest {

    @Test
    fun browsingPanelIsGlossyNearBlackAndSidebarStaysPut() {
        assertEquals(Color(0xFF0B0F14), DarkTipBg)
        assertEquals(Color(0xFF141A22), DarkTipSurface)
        assertEquals(Color(0xFF1C2430), DarkTipSurfaceAlt)

        val stops = tvContentColorStops(darkTheme = true)
        assertEquals(0f, stops.first().first)
        assertEquals(1f, stops.last().first)
        assertEquals(TipContentSheen, stops.first().second)
        assertEquals(TipContentBlackBottom, stops.last().second)
        assertEquals(Color(0xFF050505), stops.last().second)
        assertTrue(stops.first().second.red > stops.last().second.red)

        stops.forEach { (_, color) ->
            assertTrue(color.red < 0.12f, color.toString())
            assertTrue(color.green < 0.12f, color.toString())
            assertTrue(color.blue < 0.12f, color.toString())
            assertTrue(abs(color.red - color.blue) < 0.02f, color.toString())
        }
        assertTrue(stops.zipWithNext().all { (a, b) -> a.second.red >= b.second.red })
    }

    @Test
    fun lightThemeBrowsingPanelStaysAFlatPage() {
        val stops = tvContentColorStops(darkTheme = false)
        assertEquals(2, stops.size)
        assertEquals(stops.first().second, stops.last().second)
        assertEquals(Color(0xFFF4F7FB), stops.first().second)
    }
}
