package com.totaliptv.pro.desktop.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvContentThemeTest {

    @Test
    fun browsingPanelAndChromeAreFlatBlackWhileCardsStayPut() {
        assertEquals(Color(0xFF000000), DarkTipBg)
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
        assertTrue(!banner.contains("SplashBrandTitle"), "top bar must not repeat the title beside the banner")
        assertTrue(banner.contains("BannerVersionBadge"), "version stays on the banner image")
        assertEquals("v1.2.22", SplashBranding.bannerVersionLabel("1.2.22"))
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

    @Test
    fun everyScreenPaintsFlatBlackAndCardsStay() {
        val screens = listOf(
            "src/main/kotlin/com/totaliptv/pro/desktop/ui/HomeScreen.kt",
            "src/main/kotlin/com/totaliptv/pro/desktop/ui/GuideScreen.kt",
            "src/main/kotlin/com/totaliptv/pro/desktop/ui/RecordingsScreen.kt",
            "src/main/kotlin/com/totaliptv/pro/desktop/ui/SettingsScreen.kt",
            "src/main/kotlin/com/totaliptv/pro/desktop/ui/SplashScreen.kt",
            "src/main/kotlin/com/totaliptv/pro/desktop/ui/OnboardingScreen.kt",
            "src/main/kotlin/com/totaliptv/pro/desktop/ui/AppRoot.kt",
            "src/main/kotlin/com/totaliptv/pro/desktop/ui/BrowseScreen.kt"
        )
        screens.forEach { path ->
            val text = java.io.File(path).readText()
            assertTrue(text.contains("tvContentBackground()"), "$path must paint the flat screen fill")
            assertTrue(!text.contains("background(TipBg)"), "$path must not use the old page tint")
            assertTrue(!text.contains("0B0F14"), "$path must not keep the old page tint")
        }
        val browse = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/BrowseScreen.kt").readText()
        assertTrue(browse.contains(".background(TipSurface)"), "poster and row cards keep their surface")
        val dialog = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/SplitScreenDialog.kt").readText()
        assertTrue(
            dialog.contains("color = if (tipContentDark) TipContentBlack else TipSurface"),
            "Game Day dialog backdrop is flat black in the dark theme"
        )
        assertTrue(dialog.contains("else TipSurface"), "dialog channel rows keep their tile fill")
        val theme = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/Theme.kt").readText()
        assertTrue(theme.contains("TipBg = TipContentBlack"), "dark theme page color is flat black")
        assertTrue(theme.contains("background = TipContentBlack"), "material background is flat black")
    }
}
