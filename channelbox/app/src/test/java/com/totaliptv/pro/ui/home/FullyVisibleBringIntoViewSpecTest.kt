package com.totaliptv.pro.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class FullyVisibleBringIntoViewSpecTest {
    @Test
    fun alreadyFullyVisibleDoesNotScroll() {
        assertEquals(0f, fullyVisibleScrollDistance(offset = 20f, size = 80f, containerSize = 200f))
    }

    @Test
    fun clippedOnTheLeftScrollsBackToStart() {
        // Item starts 40px before the viewport; bring its leading edge to 0.
        assertEquals(-40f, fullyVisibleScrollDistance(offset = -40f, size = 80f, containerSize = 200f))
    }

    @Test
    fun clippedOnTheRightScrollsForwardJustEnough() {
        // Item occupies 180..260 in a 200px viewport; need +60 to show the trailing edge.
        assertEquals(60f, fullyVisibleScrollDistance(offset = 180f, size = 80f, containerSize = 200f))
    }

    @Test
    fun extraPaddingKeepsFocusRingInsideTheViewport() {
        assertEquals(-8f, fullyVisibleScrollDistance(offset = 0f, size = 80f, containerSize = 200f, extraPadding = 8f))
        assertEquals(8f, fullyVisibleScrollDistance(offset = 120f, size = 80f, containerSize = 200f, extraPadding = 8f))
    }
}
