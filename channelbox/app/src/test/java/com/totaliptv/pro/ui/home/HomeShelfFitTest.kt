package com.totaliptv.pro.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeShelfFitTest {
    @Test
    fun threePosterRowsFitOnShield1080p() {
        // 1920×1080 at 320 dpi (xhdpi) is 540 dp tall.
        assertEquals(540, HomeShelfFit.SHIELD_1080P_HEIGHT_DP)
        assertEquals(446f, HomeShelfFit.desktopThreeRows().value, 0.01f)
        assertEquals(486f, HomeShelfFit.classicThreeRows().value, 0.01f)
        assertTrue(HomeShelfFit.desktopThreeRows().value <= 540f)
        assertTrue(HomeShelfFit.classicThreeRows().value <= 540f)
        assertTrue(HomeShelfFit.fitsOnShield1080p())

        val root = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopAppRoot.kt").readText()
        assertFalse(root.contains("TopBanner()"))
        assertFalse(root.contains("TOTAL IPTV PRO"))
        assertTrue(root.contains("SidebarBrand()"))

        val home = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
        val pane = home.substringAfter("fun HomePane").substringBefore("fun HomeShelfHeader")
        assertFalse(pane.contains("PaneTitle(\"Home\")"))
        assertFalse(pane.contains("Continue watching and top picks"))
        assertTrue(pane.contains("HomeShelfFit.desktopPosterImage"))

        val classic = File("src/tv/java/com/totaliptv/pro/ui/home/HomeScreen.kt").readText()
        val tab = classic.substringAfter("fun HomeTabContent").substringBefore("fun HomeShelfLabel")
        assertFalse(tab.contains("HeroFeatureBanner("))
        assertTrue(tab.contains("HomeShelfFit.classicPosterImage"))
    }
}
