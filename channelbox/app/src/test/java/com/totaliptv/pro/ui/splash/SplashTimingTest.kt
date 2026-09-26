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
        val classic = java.io.File("src/tv/java/com/totaliptv/pro/ui/components/Components.kt").readText()
        val nav = classic.substringAfter("fun AppTopNav").substringBefore("fun HeroFeatureBanner")
        assertTrue(nav.contains("BannerVersionBadge"))
        assertFalse(nav.contains("text = brandTitle"))
        assertFalse(nav.contains("versionLabel("))
        val desktop = java.io.File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopComponents.kt").readText()
        val banner = desktop.substringAfter("fun TopBanner")
        assertTrue(banner.contains("BannerVersionBadge"))
        assertFalse(banner.contains("text = SplashBranding.APP_TITLE"))
        assertFalse(banner.contains("versionLabel("))
    }
}
