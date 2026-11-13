package com.totaliptv.pro.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacPlaybackOverlayTest {
    @Test
    fun overlaySitsInTheTopRightWithAStopLabel() {
        assertEquals("Stop", MacPlaybackOverlay.TITLE)
        assertTrue("Esc" in MacPlaybackOverlay.HINT)
        val size = MacPlaybackOverlay.overlaySize()
        val (x, y) = MacPlaybackOverlay.overlayOrigin(screenWidth = 1920, insetTop = 25, size = size)
        assertEquals(1920 - size.width - 24, x)
        assertTrue(y >= 28)
        val (xSmall, _) = MacPlaybackOverlay.overlayOrigin(screenWidth = 100, insetTop = 0, size = size)
        assertTrue(xSmall >= 24)
    }
}
