package com.totaliptv.pro.desktop.player

import com.totaliptv.pro.desktop.player.SplitSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class WindowPositionerTest {

    @Test
    fun dwmInsetsPushTheOuterRectPastTheVisibleHalves() {
        val insets = WindowPositioner.FrameInsets(left = 7, top = 0, right = 7, bottom = 7)
        val left = WindowPositioner.outerForVisibleTarget(0, 0, 960, 1080, insets)
        val right = WindowPositioner.outerForVisibleTarget(960, 0, 960, 1080, insets)
        assertEquals(-7, left.x)
        assertEquals(0, left.y)
        assertEquals(974, left.width)
        assertEquals(1087, left.height)
        assertEquals(953, right.x)
        assertEquals(974, right.width)

        val leftVisibleRight = left.x + left.width - insets.right
        val rightVisibleLeft = right.x + insets.left
        assertEquals(960, leftVisibleRight)
        assertEquals(960, rightVisibleLeft)
    }

    @Test
    fun extendedFrameDifferenceIsTheResizeBorder() {
        val outer = WindowPositioner.ScreenBounds(-7, 0, 974, 1087)
        val visible = WindowPositioner.ScreenBounds(0, 0, 960, 1080)
        val insets = WindowPositioner.insetsBetween(outer, visible)
        assertEquals(WindowPositioner.FrameInsets(7, 0, 7, 7), insets)
        val again = WindowPositioner.outerForVisibleTarget(0, 0, 960, 1080, insets)
        assertEquals(outer, again)
    }

    @Test
    fun resizeBorderScalesFromSevenPixelsAt96Dpi() {
        assertEquals(7, WindowPositioner.resizeBorderPx(96))
        assertEquals(7, WindowPositioner.resizeBorderPx(0))
        assertEquals(11, WindowPositioner.resizeBorderPx(144))
        assertEquals(14, WindowPositioner.resizeBorderPx(192))
        assertEquals(
            WindowPositioner.FrameInsets(7, 0, 7, 7),
            WindowPositioner.fallbackFrameInsets(96)
        )
        assertEquals(
            WindowPositioner.FrameInsets(14, 0, 14, 14),
            WindowPositioner.fallbackFrameInsets(192)
        )
    }

    @Test
    fun captionSizedInsetsFallBackToTheDpiBorder() {
        val caption = WindowPositioner.FrameInsets(left = 7, top = 31, right = 7, bottom = 7)
        assertEquals(WindowPositioner.fallbackFrameInsets(96), WindowPositioner.usableInsets(caption, 96))
        assertEquals(WindowPositioner.fallbackFrameInsets(96), WindowPositioner.usableInsets(null, 96))
        val measured = WindowPositioner.FrameInsets(8, 0, 8, 8)
        assertEquals(measured, WindowPositioner.usableInsets(measured, 96))
    }

    @Test
    fun measured3440x1440GapClosesWhenVisibleFramesMeet() {
        val work = WindowPositioner.ScreenBounds(0, 0, 3440, 1392)
        val (left, right) = WindowPositioner.splitHalves(work)
        assertEquals(WindowPositioner.ScreenBounds(0, 0, 1720, 1392), left)
        assertEquals(WindowPositioner.ScreenBounds(1720, 0, 1720, 1392), right)

        val leftWindow = WindowPositioner.ScreenBounds(0, 0, 1720, 1392)
        val leftVisible = WindowPositioner.ScreenBounds(7, 0, 1706, 1385)
        val insets = WindowPositioner.insetsBetween(leftWindow, leftVisible)
        assertEquals(WindowPositioner.FrameInsets(7, 0, 7, 7), insets)

        val leftOuter = WindowPositioner.outerForVisibleTarget(left.x, left.y, left.width, left.height, insets)
        val rightOuter = WindowPositioner.outerForVisibleTarget(right.x, right.y, right.width, right.height, insets)
        assertEquals(WindowPositioner.ScreenBounds(-7, 0, 1734, 1399), leftOuter)
        assertEquals(WindowPositioner.ScreenBounds(1713, 0, 1734, 1399), rightOuter)

        fun visible(outer: WindowPositioner.ScreenBounds): WindowPositioner.ScreenBounds {
            return WindowPositioner.ScreenBounds(
                outer.x + insets.left,
                outer.y + insets.top,
                outer.width - insets.left - insets.right,
                outer.height - insets.top - insets.bottom
            )
        }
        assertEquals(left, visible(leftOuter))
        assertEquals(right, visible(rightOuter))
        assertEquals(0, visible(leftOuter).x)
        assertEquals(1720, visible(leftOuter).x + visible(leftOuter).width)
        assertEquals(1720, visible(rightOuter).x)
        assertEquals(3440, visible(rightOuter).x + visible(rightOuter).width)
        assertEquals(1392, visible(leftOuter).height)
    }

    @Test
    fun captionedThickFrameStyleBecomesBorderless() {
        val stripped = WindowPositioner.borderlessStyle(0x9ECF0000L)
        assertEquals(0x9E000000L, stripped)
        assertEquals(0L, stripped and 0x00C00000L)
        assertEquals(0L, stripped and 0x00040000L)
        assertTrue(WindowPositioner.borderlessStyle(stripped) == stripped)
        val alreadyBorderless = WindowPositioner.FrameInsets(0, 0, 0, 0)
        val exact = WindowPositioner.outerForVisibleTarget(0, 0, 1720, 1392, alreadyBorderless)
        assertEquals(WindowPositioner.ScreenBounds(0, 0, 1720, 1392), exact)
    }

    @Test
    fun linuxPlaybackFollowsTheAppMonitor() {
        val hdmi = WindowPositioner.ScreenBounds(0, 0, 1920, 1080)
        val dp1 = WindowPositioner.ScreenBounds(1920, 0, 1920, 1080)
        assertEquals(hdmi, WindowPositioner.monitorOrFallback(hdmi, dp1))
        assertEquals(dp1, WindowPositioner.monitorOrFallback(null, dp1))

        val gtk = listOf(
            hdmi,
            WindowPositioner.ScreenBounds(1920, 40, 1920, 993)
        )
        val (left, right) = WindowPositioner.splitHalves(hdmi)
        assertEquals(
            WindowPositioner.ScreenBounds(0, 0, 960, 1080),
            LinuxX11WindowPlacer.halfInsideWorkArea(left, hdmi, gtk, null)
        )
        assertEquals(
            WindowPositioner.ScreenBounds(960, 0, 960, 1080),
            LinuxX11WindowPlacer.halfInsideWorkArea(right, hdmi, gtk, null)
        )

        val (dpLeft, dpRight) = WindowPositioner.splitHalves(dp1)
        assertEquals(
            WindowPositioner.ScreenBounds(1920, 40, 960, 993),
            LinuxX11WindowPlacer.halfInsideWorkArea(dpLeft, dp1, gtk, null)
        )
        assertEquals(
            WindowPositioner.ScreenBounds(2880, 40, 960, 993),
            LinuxX11WindowPlacer.halfInsideWorkArea(dpRight, dp1, gtk, null)
        )

        assertTrue(LinuxX11WindowPlacer.fullscreenCoversMonitor(WindowPositioner.ScreenBounds(0, 0, 1920, 1080), hdmi))
        assertFalse(LinuxX11WindowPlacer.fullscreenCoversMonitor(dp1, hdmi))
        assertFalse(LinuxX11WindowPlacer.fullscreenCoversMonitor(WindowPositioner.ScreenBounds(0, 0, 800, 600), hdmi))
        assertEquals(
            WindowPositioner.ScreenBounds(1920, 40, 1920, 993),
            LinuxX11WindowPlacer.usableWorkArea(dp1, gtk, null)
        )
    }

    @Test
    fun linuxSplitAudioFollowsFocus() {
        val focus = LinuxX11WindowPlacer.SplitFocusAudio()
        val left = 10L
        val right = 20L
        assertEquals(null, focus.onActive(right, left, right))
        assertEquals(SplitSide.LEFT, focus.onActive(left, left, right))
        assertEquals(null, focus.onActive(left, left, right))
        assertEquals(SplitSide.RIGHT, focus.onActive(right, left, right))
        assertEquals(null, focus.onActive(0L, left, right))
        assertEquals(null, LinuxX11WindowPlacer.splitSideForWindow(99L, left, right))
        val placer = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/player/LinuxX11WindowPlacer.kt")
        val text = placer.readText()
        assertFalse(text.contains("XSelectInput"))
        assertFalse(text.contains("ButtonPress"))
    }

    @Test
    fun fullscreenIgnoresTheHiddenQtWindowAndRequiresTheFullMonitor() {
        val hidden = 0x1L
        val video = 0x2L
        val owned = listOf(hidden, video)
        assertEquals(listOf(video), LinuxX11WindowPlacer.wmManagedWindows(owned, setOf(video)))
        assertEquals(emptyList(), LinuxX11WindowPlacer.wmManagedWindows(owned, emptySet()))
        assertEquals(owned, LinuxX11WindowPlacer.wmManagedWindows(owned, null))

        val hdmi = WindowPositioner.ScreenBounds(0, 0, 1920, 1080)
        val dp1 = WindowPositioner.ScreenBounds(1920, 0, 1920, 1080)
        val hdmiFull = WindowPositioner.ScreenBounds(0, 0, 1920, 1080)
        val dpFull = WindowPositioner.ScreenBounds(1920, 0, 1920, 1080)
        assertTrue(
            LinuxX11WindowPlacer.fullscreenSettled(true, hdmiFull, true, hdmiFull, hdmi)
        )
        assertTrue(
            LinuxX11WindowPlacer.fullscreenSettled(true, dpFull, true, WindowPositioner.ScreenBounds(1922, 2, 1918, 1078), dp1)
        )
        assertFalse(
            LinuxX11WindowPlacer.fullscreenSettled(
                true,
                WindowPositioner.ScreenBounds(0, 37, 1920, 1043),
                true,
                WindowPositioner.ScreenBounds(0, 37, 1920, 1043),
                hdmi
            )
        )
        assertFalse(
            LinuxX11WindowPlacer.fullscreenSettled(
                true,
                WindowPositioner.ScreenBounds(1920, 77, 1920, 956),
                true,
                WindowPositioner.ScreenBounds(1920, 77, 1920, 956),
                dp1
            )
        )
        assertFalse(
            LinuxX11WindowPlacer.fullscreenSettled(true, hdmiFull, false, hdmiFull, hdmi)
        )
        assertFalse(
            LinuxX11WindowPlacer.fullscreenSettled(true, hdmiFull, true, dpFull, hdmi)
        )
    }

    @Test
    fun linuxHalvesStayInsideTheMonitorWorkArea() {
        val hdmi = WindowPositioner.ScreenBounds(0, 0, 1920, 1080)
        val dp1 = WindowPositioner.ScreenBounds(1920, 0, 1920, 1080)
        val gtk = listOf(
            WindowPositioner.ScreenBounds(0, 0, 1920, 1080),
            WindowPositioner.ScreenBounds(1920, 40, 1920, 993)
        )
        val dpWork = LinuxX11WindowPlacer.usableWorkArea(dp1, gtk, null)
        assertEquals(WindowPositioner.ScreenBounds(1920, 40, 1920, 993), dpWork)
        val (left, right) = LinuxX11WindowPlacer.halvesInWorkArea(dp1, dpWork)
        assertEquals(WindowPositioner.ScreenBounds(1920, 40, 960, 993), left)
        assertEquals(WindowPositioner.ScreenBounds(2880, 40, 960, 993), right)
        assertEquals(right.x, left.x + left.width)

        val requestedLeft = WindowPositioner.ScreenBounds(1920, 0, 960, 1080)
        val requestedRight = WindowPositioner.ScreenBounds(2880, 0, 960, 1080)
        assertEquals(left, LinuxX11WindowPlacer.halfInsideWorkArea(requestedLeft, dp1, gtk, null))
        assertEquals(right, LinuxX11WindowPlacer.halfInsideWorkArea(requestedRight, dp1, gtk, null))

        val hdmiWork = LinuxX11WindowPlacer.usableWorkArea(hdmi, gtk, null)
        assertEquals(hdmi, hdmiWork)

        val netOnly = LinuxX11WindowPlacer.usableWorkArea(
            dp1,
            emptyList(),
            WindowPositioner.ScreenBounds(0, 40, 3840, 993)
        )
        assertEquals(WindowPositioner.ScreenBounds(1920, 40, 1920, 993), netOnly)
        val (netLeft, netRight) = WindowPositioner.splitHalves(netOnly)
        assertEquals(WindowPositioner.ScreenBounds(1920, 40, 960, 993), netLeft)
        assertEquals(WindowPositioner.ScreenBounds(2880, 40, 960, 993), netRight)

        assertEquals(dp1, LinuxX11WindowPlacer.usableWorkArea(dp1, emptyList(), null))

        val gtkValues = listOf(0L, 0L, 1920L, 1080L, 1920L, 40L, 1920L, 993L)
        assertEquals(gtk, LinuxX11WindowPlacer.cardinalRects(gtkValues))
        val netValues = listOf(0L, 40L, 3840L, 993L)
        assertEquals(
            WindowPositioner.ScreenBounds(0, 40, 3840, 993),
            LinuxX11WindowPlacer.cardinalRect(netValues, 0)
        )

        assertTrue(
            LinuxX11WindowPlacer.geometryMatches(
                WindowPositioner.ScreenBounds(1922, 42, 958, 991),
                WindowPositioner.ScreenBounds(1920, 40, 960, 993)
            )
        )
        assertFalse(
            LinuxX11WindowPlacer.geometryMatches(
                WindowPositioner.ScreenBounds(960, 0, 960, 1080),
                WindowPositioner.ScreenBounds(1920, 40, 960, 993)
            )
        )
    }

    @Test
    fun xresClientIdSpecMatchesLp64Layout() {
        assertEquals(16, LinuxX11WindowPlacer.xresSpecSize())
        assertEquals(0, LinuxX11WindowPlacer.xresSpecFieldOffset("client"))
        assertEquals(8, LinuxX11WindowPlacer.xresSpecFieldOffset("mask"))
    }

    @Test
    fun linuxX11MovesItsOwnWindowWhenDisplayIsOpen() {
        if (System.getenv("DISPLAY").isNullOrBlank()) return
        val lib = LinuxX11WindowPlacer.findLibrary("libX11.so.6")
        if (lib == null) return
        assertTrue(lib.endsWith("libX11.so.6") || lib.contains("libX11.so.6"))
        val placed = LinuxX11WindowPlacer.placeSyntheticWindow(40, 60, 320, 180)
        assertTrue(placed.available, placed.detail)
        assertEquals("xres", placed.pidSource, placed.detail)
        assertEquals(40, placed.x, placed.detail)
        assertEquals(60, placed.y, placed.detail)
        assertEquals(320, placed.width, placed.detail)
        assertEquals(180, placed.height, placed.detail)
        assertFalse(placed.detail.isBlank())
    }
}
