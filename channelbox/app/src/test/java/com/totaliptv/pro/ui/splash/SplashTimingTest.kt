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
        assertEquals(1280f / 720f, SplashBranding.BANNER_ASPECT_RATIO, 0.0001f)
        val splash = java.io.File("src/main/java/com/totaliptv/pro/ui/splash/LogoSplash.kt").readText()
        assertFalse(splash.contains("BannerVersionBadge"))
        assertFalse(splash.contains("bannerVersionLabel"))
        val art = splash.substringAfter("fun AppBannerArt").substringBefore("object StartupSplashGate")
        assertTrue(art.contains("ContentScale.Fit"))
        assertTrue(art.contains(".clip(RoundedCornerShape(8.dp))"))
        assertFalse(art.contains("ContentScale.Crop"))
        assertFalse(art.contains("Text("))
        assertFalse(art.contains("text = SplashBranding.APP_TITLE"))
        val splashBody = splash.substringAfter("fun LogoBannerSplash")
        assertTrue(splashBody.contains("AppBannerArt("))
        assertFalse(splashBody.contains("BannerVersionBadge"))
        assertFalse(splashBody.contains("text = SplashBranding.APP_TITLE"))
        val classic = java.io.File("src/tv/java/com/totaliptv/pro/ui/components/Components.kt").readText()
        val nav = classic.substringAfter("fun AppTopNav").substringBefore("fun HeroFeatureBanner")
        assertTrue(nav.contains("AppBannerArt("))
        assertTrue(nav.contains("DesktopBannerImageHeight"))
        assertFalse(nav.contains("BannerVersionBadge"))
        assertFalse(nav.contains("text = brandTitle"))
        assertFalse(nav.contains("versionLabel("))
        assertFalse(nav.contains("versionName"))
        assertFalse(nav.contains("ContentScale.Crop"))
        assertFalse(nav.contains("height(52.dp)"))
        val desktop = java.io.File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopComponents.kt").readText()
        val banner = desktop.substringAfter("fun TopBanner")
        assertTrue(banner.contains("AppBannerArt("))
        assertTrue(banner.contains("DesktopBannerRowHeight"))
        assertTrue(banner.contains("vertical = 8.dp"))
        assertFalse(banner.contains("BannerVersionBadge"))
        assertFalse(banner.contains("text = SplashBranding.APP_TITLE"))
        assertFalse(banner.contains("versionLabel("))
        assertFalse(banner.contains("ContentScale.Crop"))
        val settings = java.io.File("src/tv/java/com/totaliptv/pro/ui/settings/SettingsScreen.kt").readText()
        val desktopSettings = java.io.File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
        val phoneSettings = java.io.File("src/phone/java/com/totaliptv/pro/ui/settings/PhoneSettingsScreen.kt").readText()
        assertTrue(settings.contains("Installed: \${BuildConfig.VERSION_NAME}"))
        assertTrue(desktopSettings.contains("Installed: \${BuildConfig.VERSION_NAME}"))
        assertTrue(phoneSettings.contains("Total IPTV Pro Phone \${BuildConfig.VERSION_NAME}"))
    }
}
