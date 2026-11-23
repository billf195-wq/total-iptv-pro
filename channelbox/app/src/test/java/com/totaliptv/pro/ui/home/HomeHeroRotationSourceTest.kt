package com.totaliptv.pro.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeHeroRotationSourceTest {
    @Test
    fun heroBannerIsASingleDailyPickWithoutRotation() {
        val banner = File("src/tv/java/com/totaliptv/pro/ui/home/HomeHeroBanner.kt").readText()
        assertFalse(banner.contains("ROTATION_INTERVAL_MS"))
        assertFalse(banner.contains("ROTATION_CROSSFADE_MS"))
        assertFalse(banner.contains("canRotate"))
        assertFalse(banner.contains("HeroCrossfadeStills"))
        assertFalse(banner.contains("HeroCrossfadeCopy"))
        assertFalse(banner.contains("fun rotation"))
        assertFalse(banner.contains("LaunchedEffect"))
        // One still only, downscaled the same way as the 181,571 KB layout.
        assertEquals(1, banner.split("NetworkImage(").size - 1)
        assertTrue(banner.contains("Alignment.TopCenter"))
        assertTrue(banner.contains("ContentScale.Crop"))
        assertTrue(banner.contains("coerceAtMost(960)"))
        assertTrue(banner.contains("coerceAtMost(640)"))
        assertTrue(banner.contains("rgb565 = true"))
        assertFalse(banner.contains("requestFocus("))

        val pickSrc = File("src/main/java/com/totaliptv/pro/data/HeroBannerPick.kt").readText()
        assertFalse(pickSrc.contains("ROTATION_COUNT"))
        assertFalse(pickSrc.contains("ROTATION_INTERVAL_MS"))
        assertFalse(pickSrc.contains("fun rotation("))

        val classic = File("src/tv/java/com/totaliptv/pro/ui/home/HomeScreen.kt").readText()
        assertTrue(classic.contains("HeroBannerPick.pick("))
        assertFalse(classic.contains("HeroBannerPick.rotation"))
        assertFalse(classic.contains("Welcome to Total IPTV Pro"))

        val pane = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
            .substringAfter("fun HomePane")
            .substringBefore("fun HomeShelfHeader")
        assertTrue(pane.contains("HeroBannerPick.pick("))
        assertFalse(pane.contains("HeroBannerPick.rotation"))
        assertTrue(pane.contains("HomeHeroBanner("))
        assertTrue(pane.contains("HomeShelfFit.desktopHomeBannerTop"))
        assertFalse(pane.contains("Top rated movies"))
        assertFalse(File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
            .contains("Welcome to Total IPTV Pro"))
    }
}
