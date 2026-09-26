package com.totaliptv.pro.ui.splash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashTimingTest {
    @Test
    fun logoBannerIsFifteenSecondsNotCatalogHold() {
        assertEquals(15_000L, SplashTiming.DURATION_MS)
        assertEquals(30_000L, SplashTiming.MAX_CATALOG_HOLD_MS)
        assertTrue(SplashTiming.MAX_CATALOG_HOLD_MS > SplashTiming.DURATION_MS)
    }

    @Test
    fun splashTitleKeepsNameAndSmallerVersionSuffix() {
        assertEquals("Total IPTV Pro", SplashBranding.APP_TITLE)
        assertEquals("1.4.57", SplashBranding.versionLabel("1.4.57"))
        assertEquals("1.4.34-phone", SplashBranding.versionLabel(" 1.4.34-phone "))
        assertEquals("v1.4.63", SplashBranding.bannerVersionLabel("1.4.63"))
        assertEquals("v1.4.40-phone", SplashBranding.bannerVersionLabel(" 1.4.40-phone "))
        assertEquals("", SplashBranding.bannerVersionLabel("  "))
        assertEquals(1280f / 720f, SplashBranding.BANNER_ASPECT_RATIO, 0.0001f)
        val splash = java.io.File("src/main/java/com/totaliptv/pro/ui/splash/LogoSplash.kt").readText()
        val badge = splash.substringAfter("fun BannerVersionBadge").substringBefore("fun AppBannerArt")
        assertTrue(badge.contains("fontSize = 12.sp"))
        assertTrue(badge.contains("horizontal = 8.dp, vertical = 3.dp"))
        assertTrue(badge.contains("fontScale = 1f"))
        val art = splash.substringAfter("fun AppBannerArt").substringBefore("object StartupSplashGate")
        assertTrue(art.contains("ContentScale.Fit"))
        assertTrue(art.contains("Alignment.BottomEnd"))
        assertTrue(art.contains("padding(end = 8.dp, bottom = 6.dp)"))
        assertTrue(art.contains(".clip(RoundedCornerShape(8.dp))"))
        assertFalse(art.contains("ContentScale.Crop"))
        assertFalse(art.contains("text = SplashBranding.APP_TITLE"))
        val splashBody = splash.substringAfter("fun LogoBannerSplash")
        assertTrue(splashBody.contains("AppBannerArt("))
        assertFalse(splashBody.contains("text = SplashBranding.APP_TITLE"))
        val classic = java.io.File("src/tv/java/com/totaliptv/pro/ui/components/Components.kt").readText()
        val nav = classic.substringAfter("fun AppTopNav").substringBefore("fun HeroFeatureBanner")
        assertTrue(nav.contains("AppBannerArt("))
        assertTrue(nav.contains("DesktopBannerImageHeight"))
        assertFalse(nav.contains("text = brandTitle"))
        assertFalse(nav.contains("versionLabel("))
        assertFalse(nav.contains("ContentScale.Crop"))
        assertFalse(nav.contains("height(52.dp)"))
        val desktop = java.io.File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopComponents.kt").readText()
        val banner = desktop.substringAfter("fun TopBanner")
        assertTrue(banner.contains("AppBannerArt("))
        assertTrue(banner.contains("DesktopBannerRowHeight"))
        assertTrue(banner.contains("vertical = 8.dp"))
        assertFalse(banner.contains("text = SplashBranding.APP_TITLE"))
        assertFalse(banner.contains("versionLabel("))
        assertFalse(banner.contains("ContentScale.Crop"))
    }
}
