package com.totaliptv.pro.ui.splash

import org.junit.Assert.assertEquals
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
        assertEquals("1.4.49", SplashBranding.versionLabel("1.4.49"))
        assertEquals("1.4.26-phone", SplashBranding.versionLabel(" 1.4.26-phone "))
    }
}
