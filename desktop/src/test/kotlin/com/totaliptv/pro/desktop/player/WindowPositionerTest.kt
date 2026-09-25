package com.totaliptv.pro.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
