package com.totaliptv.pro.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}
