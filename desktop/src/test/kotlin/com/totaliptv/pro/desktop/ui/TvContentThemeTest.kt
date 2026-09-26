package com.totaliptv.pro.desktop.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class TvContentThemeTest {

    @Test
    fun browsingPanelIsFlatBlackAndSidebarStaysPut() {
        assertEquals(Color(0xFF0B0F14), DarkTipBg)
        assertEquals(Color(0xFF141A22), DarkTipSurface)
        assertEquals(Color(0xFF1C2430), DarkTipSurfaceAlt)
        assertEquals(Color(0xFF000000), TipContentBlack)
        assertEquals(Color(0xFF000000), tvContentColor(darkTheme = true))
    }

    @Test
    fun lightThemeBrowsingPanelStaysAFlatPage() {
        assertEquals(Color(0xFFF4F7FB), tvContentColor(darkTheme = false))
    }
}
