package com.totaliptv.pro.ui.home

import androidx.compose.ui.unit.dp
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
        assertEquals(438f, HomeShelfFit.desktopHomeBlock().value, 0.01f)
        assertEquals(51f, HomeShelfFit.desktopHomeTopOffset().value, 0.01f)
        assertEquals(486f, HomeShelfFit.classicThreeRows().value, 0.01f)
        assertEquals(500f, HomeShelfFit.classicHomeArea().value, 0.01f)
        assertEquals(438f, HomeShelfFit.classicHomeBlock().value, 0.01f)
        assertEquals(31f, HomeShelfFit.classicHomeTopOffset().value, 0.01f)
        assertEquals(
            4f,
            HomeShelfFit.centeredTopOffset(400.dp, 500.dp, 4.dp).value,
            0.01f
        )
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
        assertTrue(pane.contains("HomeShelfFit.desktopHomeBlock()"))
        assertTrue(pane.contains("centeredTopOffset"))
        assertTrue(pane.contains("userScrollEnabled = !centerHome"))
        assertTrue(root.contains("section == DesktopNavSection.HOME"))

        val classic = File("src/tv/java/com/totaliptv/pro/ui/home/HomeScreen.kt").readText()
        val tab = classic.substringAfter("fun HomeTabContent").substringBefore("fun HomeShelfLabel")
        assertFalse(tab.contains("HeroFeatureBanner("))
        assertTrue(tab.contains("HomeShelfFit.classicPosterImage"))
        assertTrue(tab.contains("centeredTopOffset"))
        assertTrue(tab.contains("userScrollEnabled = !centerHome"))
        assertTrue(classic.contains("modifier = Modifier.weight(1f)"))
    }

    @Test
    fun everyPageSharesTheSameTopOffset() {
        assertEquals(4f, HomeShelfFit.pageTopOffset.value, 0.01f)
        assertEquals(4f, HomeShelfFit.desktopContentPadTop.value, 0.01f)
        assertEquals(16f, HomeShelfFit.desktopRowHeader.value, 0.01f)
        assertEquals(40f, HomeShelfFit.phoneTopOffset.value, 0.01f)
        assertEquals(40f, HomeShelfFit.classicNav.value, 0.01f)
        assertEquals(HomeShelfFit.phoneTopOffset, HomeShelfFit.classicNav)

        val root = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopAppRoot.kt").readText()
        assertTrue(root.contains("top = HomeShelfFit.pageTopOffset"))
        assertFalse(root.contains("TopBanner()"))
        assertTrue(root.contains("SidebarBrand()"))
        assertEquals(2, Regex("applyPageInset = false").findAll(root).count())

        val panes = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
        assertFalse(panes.contains("PaneTitle(\"Live\")"))
        assertFalse(panes.contains("PaneTitle(title)"))
        assertFalse(panes.contains("PaneTitle(\"Favorites\")"))
        assertFalse(panes.contains("PaneTitle(\"Settings\")"))
        val live = panes.substringAfter("fun LivePane").substringBefore("fun sortDesktopRecentlyAdded")
        val liveColumn = live.substringAfter("Column(")
        assertTrue(liveColumn.contains("FilterBar("))
        assertTrue(liveColumn.indexOf("FilterBar(") < liveColumn.indexOf("label = \"Game Day\""))
        val grid = panes.substringAfter("Column(Modifier.fillMaxSize()) {\n        FilterBar(")
        assertTrue(grid.startsWith("\n            search = search"))
        val shelfHeader = panes.substringAfter("fun HomeShelfHeader").substringBefore("fun LivePane")
        assertTrue(shelfHeader.contains("PaneTitle(text)"))

        val paneTitle = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopComponents.kt").readText()
            .substringAfter("fun PaneTitle")
            .substringBefore("fun AmberButton")
        assertTrue(paneTitle.contains("HomeShelfFit.desktopRowHeader"))

        val guide = File("src/tv/java/com/totaliptv/pro/ui/epg/EpgGuideScreen.kt").readText()
        assertTrue(guide.contains("HomeShelfFit.pageTopOffset"))
        assertFalse(guide.contains("PaneTitle(\"TV Guide\")"))
        assertFalse(guide.contains("ClassicPageTitle("))
        assertTrue(guide.contains("ClassicBrandBar()"))

        val recordings = File("src/tv/java/com/totaliptv/pro/ui/dvr/RecordingsScreen.kt").readText()
        assertTrue(recordings.contains("HomeShelfFit.pageTopOffset"))
        assertFalse(recordings.contains("PaneTitle(\"Recordings\""))
        assertFalse(recordings.contains("ClassicPageTitle("))
        assertTrue(recordings.contains("ClassicBrandBar()"))

        val classicPages = listOf(
            "src/tv/java/com/totaliptv/pro/ui/home/HomeScreen.kt",
            "src/tv/java/com/totaliptv/pro/ui/browse/CategoryScreen.kt",
            "src/tv/java/com/totaliptv/pro/ui/settings/SettingsScreen.kt",
            "src/tv/java/com/totaliptv/pro/ui/search/SearchScreen.kt"
        )
        val home = File(classicPages[0]).readText()
        assertTrue(home.contains("AppTopNav("))
        assertTrue(home.contains("HomeShelfFit.classicContentPadTop"))
        val nav = File("src/tv/java/com/totaliptv/pro/ui/components/Components.kt").readText()
            .substringAfter("fun AppTopNav")
            .substringBefore("fun ClassicBrandBar")
        assertTrue(nav.contains("HomeShelfFit.classicNav"))
        assertTrue(nav.contains("AppBrandName("))
        assertFalse(nav.contains("AppBannerArt("))
        for (path in classicPages.drop(1)) {
            val text = File(path).readText()
            assertTrue(path, text.contains("ClassicBrandBar()"))
            assertTrue(path, text.contains("HomeShelfFit.pageTopOffset"))
            assertFalse(path, text.contains("ClassicPageTitle("))
        }
        val search = File(classicPages[3]).readText()
        val searchBody = search.substringAfter("ClassicBrandBar()")
        assertTrue(searchBody.indexOf("DpadSearchField(") < searchBody.indexOf("label = \"Close\""))
        assertTrue(search.contains("HomeShelfFit.searchFieldCorner"))
        assertTrue(panes.contains("HomeShelfFit.searchFieldCorner"))
        assertTrue(panes.contains("fontSize = 16.sp"))
        assertEquals(48f, HomeShelfFit.searchFieldHeight.value, 0.01f)
        assertTrue(
            HomeShelfFit.searchFieldHeight.value >=
                HomeShelfFit.searchFieldPadV.value * 2 + 20f
        )

        val header = File("src/phone/java/com/totaliptv/pro/ui/components/PhoneComponents.kt").readText()
            .substringAfter("fun PhonePageHeader")
            .substringBefore("fun PhoneSectionTitle")
        assertTrue(header.contains("HomeShelfFit.phoneTopOffset"))
        assertTrue(header.contains("AppBrandName("))
        assertFalse(header.contains("AppBannerArt("))
        assertTrue(header.contains("Color(0xFF000000)"))
        assertFalse(header.contains("text = title"))
        assertEquals(18f, HomeShelfFit.brandNameSp.value, 0.01f)
        assertTrue(HomeShelfFit.brandNameFitsSidebar())
        val rootPad = root.substringAfter("fun DesktopSidebar")
        assertTrue(rootPad.contains("top = HomeShelfFit.pageTopOffset"))
        val phonePages = listOf(
            "src/phone/java/com/totaliptv/pro/ui/home/PhoneHomeScreen.kt",
            "src/phone/java/com/totaliptv/pro/ui/browse/PhoneBrowseScreen.kt",
            "src/phone/java/com/totaliptv/pro/ui/search/PhoneSearchScreen.kt",
            "src/phone/java/com/totaliptv/pro/ui/dvr/PhoneRecordingsScreen.kt",
            "src/phone/java/com/totaliptv/pro/ui/settings/PhoneSettingsScreen.kt"
        )
        for (path in phonePages) {
            assertTrue(path, File(path).readText().contains("PhonePageHeader("))
        }
    }
}
