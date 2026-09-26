package com.totaliptv.pro.desktop.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvContentThemeTest {

    @Test
    fun browsingPanelAndChromeAreFlatBlackWhileCardsStayPut() {
        assertEquals(Color(0xFF0B0F14), DarkTipBg)
        assertEquals(Color(0xFF141A22), DarkTipSurface)
        assertEquals(Color(0xFF1C2430), DarkTipSurfaceAlt)
        assertEquals(Color(0xFF000000), TipContentBlack)
        assertEquals(Color(0xFF000000), tvContentColor(darkTheme = true))
        assertEquals(Color(0xFF000000), tvChromeColor(darkTheme = true))
    }

    @Test
    fun lightThemeBrowsingPanelStaysAFlatPage() {
        assertEquals(Color(0xFFF4F7FB), tvContentColor(darkTheme = false))
        assertEquals(Color(0xFFFFFFFF), tvChromeColor(darkTheme = false))
    }

    @Test
    fun topBarAndSidebarUseChromeBlackWhileNavAndUpdateStay() {
        val browse = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/BrowseScreen.kt").readText()
        val nav = browse.substringAfter("private fun NavBtn(").substringBefore("private fun MediaRow(")
        val banner = browse.substringAfter("private fun TopBanner(").substringBefore("@Composable\nprivate fun BrowseContentPane")
        assertTrue(banner.contains(".tvChromeBackground()"), "top bar must use the flat chrome fill")
        assertTrue(
            browse.contains(".fillMaxHeight()\n                    .tvChromeBackground()"),
            "sidebar panel must use the flat chrome fill"
        )
        assertTrue(nav.contains("TipBlue.copy(alpha = 0.25f)") && nav.contains("TipSurfaceAlt"), "nav buttons keep their own fill")
        assertTrue(browse.contains("Text(\"Update\""), "Update button label stays")
        val update = browse.substringBefore("Text(\"Update\"")
        assertTrue(
            update.substringAfterLast("Button(").contains("containerColor = TipBlue"),
            "Update button keeps the amber fill"
        )
    }
}
