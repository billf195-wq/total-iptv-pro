package com.totaliptv.pro.desktop.ui

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextEpisodeBannerLayoutTest {
    private val linux = BannerCopy(
        kicker = "NEXT EPISODE",
        title = "The Show",
        nowLine = "Now S1E1",
        primaryLabel = "Next S1E2",
        recordLabel = "Record",
        hint = NextEpisodeBannerLayout.LINUX_HINT
    )

    @Test
    fun measuredHeightFitsTheTextAndGrowsWithGnomeTextScale() {
        val normal = NextEpisodeBannerLayout.measure(1.0, 1.0, linux, AwtBannerTextMeasurer)
        val scaled = NextEpisodeBannerLayout.measure(1.0, 1.25, linux, AwtBannerTextMeasurer)
        val hidpi = NextEpisodeBannerLayout.measure(2.0, 1.25, linux, AwtBannerTextMeasurer)
        assertTrue(normal.fitsText(), "window and button must cover the measured text plus padding")
        assertTrue(scaled.fitsText())
        assertTrue(hidpi.fitsText())
        assertTrue(scaled.heightPx > normal.heightPx, "GNOME text-scaling-factor must enlarge the window")
        assertTrue(scaled.buttonHeightPx >= normal.buttonHeightPx)
        assertTrue(hidpi.heightPx > scaled.heightPx, "HiDPI density must enlarge the window")
        assertTrue(normal.buttonHeightPx >= normal.buttonTextPx + normal.buttonPadPx)
        assertTrue(NextEpisodeBannerLayout.BUTTON_SP < 16f)
        assertTrue(NextEpisodeBannerLayout.KICKER_SP < 13f)
    }

    @Test
    fun windowsUsesTheSameMeasurementAsLinux() {
        val textScale = 1.5
        val linuxSize = NextEpisodeBannerLayout.measure(1.0, textScale, linux, AwtBannerTextMeasurer)
        val windowsSize = NextEpisodeBannerLayout.measure(
            1.0,
            textScale,
            linux.copy(hint = NextEpisodeBannerLayout.WINDOWS_HINT, primaryLabel = "Last episode of this series"),
            AwtBannerTextMeasurer
        )
        assertTrue(linuxSize.fitsText())
        assertTrue(windowsSize.fitsText())
        assertTrue(linuxSize.heightPx >= windowsSize.heightPx)
        val overlay = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/SeriesNextOverlay.kt").readText()
        assertTrue(overlay.contains("NextEpisodeBannerLayout.measure"))
        assertTrue(overlay.contains("currentDesktopTextScale"))
        assertTrue(overlay.contains("LocalDensity"))
        assertFalse(overlay.contains(".height(48.dp)"))
        assertFalse(overlay.contains("196 * scale"))
    }

    @Test
    fun tallMeasuredTextIsNotClampedToTheOldFixedHeight() {
        val tall = BannerTextMeasurer { _, fontPx, _, _, _ ->
            BannerTextSize(widthPx = 220, heightPx = ceil(fontPx * 4.0).toInt())
        }
        val size = NextEpisodeBannerLayout.measure(1.0, 1.25, linux, tall)
        assertTrue(size.fitsText())
        assertTrue(size.heightPx > 196, "a fixed 196px window would clip this text")
        assertTrue(size.buttonHeightPx > 40, "button height must follow the label, not stop at 40px")
        val bigger = NextEpisodeBannerLayout.measure(1.0, 2.0, linux, tall)
        assertTrue(bigger.heightPx > size.heightPx)
        assertTrue(bigger.buttonHeightPx > size.buttonHeightPx)
    }

    @Test
    fun parsesGnomeAndWindowsTextScale() {
        assertEquals(1.0, NextEpisodeBannerLayout.parseDesktopTextScale(null))
        assertEquals(1.0, NextEpisodeBannerLayout.parseDesktopTextScale("1.0"))
        assertEquals(1.25, NextEpisodeBannerLayout.parseDesktopTextScale("1.25\n"))
        assertEquals(1.25, NextEpisodeBannerLayout.parseDesktopTextScale("    TextScaleFactor    REG_DWORD    0x7d"))
        assertEquals(1.0, NextEpisodeBannerLayout.parseDesktopTextScale("0x64"))
        assertEquals(1.5, NextEpisodeBannerLayout.parseDesktopTextScale("150"))
    }
}
