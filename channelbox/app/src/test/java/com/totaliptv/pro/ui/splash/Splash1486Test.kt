package com.totaliptv.pro.ui.splash

import com.totaliptv.pro.ui.player.PlayerOverlayTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class Splash1486Test {
    @Test
    fun loadingLabelCyclesEveryFiveSeconds() {
        assertEquals("Loading EPG", SplashLoadingText.labelAt(0))
        assertEquals("Loading EPG", SplashLoadingText.labelAt(4_999))
        assertEquals("Loading VOD", SplashLoadingText.labelAt(5_000))
        assertEquals("Loading Series", SplashLoadingText.labelAt(10_000))
        assertEquals("Loading Series", SplashLoadingText.labelAt(25_000))
    }

    @Test
    fun bannersHideAfterThreeSeconds() {
        assertEquals(3_000L, PlayerOverlayTiming.AUTO_MS)
        assertEquals(3_000L, PlayerOverlayTiming.KEY_MS)
    }
}
