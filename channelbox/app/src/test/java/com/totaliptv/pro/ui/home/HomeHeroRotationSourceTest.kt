package com.totaliptv.pro.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeHeroRotationSourceTest {
    @Test
    fun heroBannerRotatesWithoutStealingFocusOrDecodingAStack() {
        val banner = File("src/tv/java/com/totaliptv/pro/ui/home/HomeHeroBanner.kt").readText()
        assertTrue(banner.contains("HeroBannerPick.ROTATION_INTERVAL_MS"))
        assertTrue(banner.contains("HeroBannerPick.ROTATION_CROSSFADE_MS"))
        assertTrue(banner.contains("playFocused"))
        assertTrue(banner.contains("infoFocused"))
        assertTrue(banner.contains("Lifecycle.State.RESUMED"))
        assertTrue(banner.contains("fun HeroCrossfadeStills"))
        // Current still plus the preloaded next still, and no more.
        val stills = banner.substringAfter("fun HeroCrossfadeStills").substringBefore("fun HeroCrossfadeCopy")
        // Current + preloaded next, each a HeroStill (one NetworkImage inside HeroStill).
        org.junit.Assert.assertEquals(2, stills.split("HeroStill(").size - 1)
        val stillBody = banner.substringAfter("private fun HeroStill").substringBefore("private fun HeroCopy")
        org.junit.Assert.assertEquals(1, stillBody.split("NetworkImage(").size - 1)
        val effect = banner.substringAfter("val canRotate").substringBefore("val shape")
        assertFalse(effect.contains("SafeFocus.request") || effect.contains(".requestFocus("))
        assertTrue(effect.contains("playFocused"))
        assertTrue(effect.contains("inForeground"))

        val classic = File("src/tv/java/com/totaliptv/pro/ui/home/HomeScreen.kt").readText()
        assertTrue(classic.contains("HeroBannerPick.rotation"))
        val pane = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
            .substringAfter("fun HomePane")
            .substringBefore("fun HomeShelfHeader")
        assertTrue(pane.contains("HeroBannerPick.rotation"))
        assertTrue(pane.contains("HomeHeroBanner("))
        assertFalse(pane.contains("Top rated movies"))
    }
}
