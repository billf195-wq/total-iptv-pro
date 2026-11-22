package com.totaliptv.pro.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenFitTest {
    @Test
    fun normalizeClampsAndDefaults() {
        assertEquals(5, ScreenFit.normalize(null))
        assertEquals(0, ScreenFit.normalize(-3))
        assertEquals(10, ScreenFit.normalize(99))
        assertEquals(5, ScreenFit.normalize(5))
        assertEquals(0, ScreenFit.normalize(0))
        assertEquals(10, ScreenFit.normalize(10))
    }

    @Test
    fun defaultFivePercentMatchesUsualTvSafeMargins() {
        // 1080p at xhdpi is 960 x 540 dp; 5% is 48 x 27, the usual TV safe margin.
        assertEquals(48f, ScreenFit.paddingDp(960, 5), 0.01f)
        assertEquals(27f, ScreenFit.paddingDp(540, 5), 0.01f)
        assertEquals(0f, ScreenFit.paddingDp(960, 0), 0.01f)
        assertEquals(96f, ScreenFit.paddingDp(960, 10), 0.01f)
        assertEquals(54f, ScreenFit.paddingDp(540, 10), 0.01f)
        assertEquals(0f, ScreenFit.paddingDp(0, 5), 0.01f)
    }

    @Test
    fun raiseFromZeroReachesDefaultAndMax() {
        assertEquals(1, ScreenFit.stepped(0, 1))
        assertEquals(5, ScreenFit.stepped(0, 5))
        assertEquals(10, ScreenFit.stepped(0, 10))
        assertEquals(10, ScreenFit.stepped(0, 11))
        assertEquals(5, ScreenFit.stepped(10, -5))
        assertEquals(0, ScreenFit.stepped(1, -1))
        assertEquals(0, ScreenFit.stepped(0, -1))
    }
}
