package com.totaliptv.pro.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvBackTest {
    @Test
    fun sectionGoesHomeAndHomeAsksTwice() {
        assertEquals(TvBack.Action.GO_HOME, TvBack.action(atHome = false, armedAtMs = 0L, nowMs = 5_000L))
        assertEquals(TvBack.Action.ARM_EXIT, TvBack.action(atHome = true, armedAtMs = 0L, nowMs = 5_000L))
        assertEquals(
            TvBack.Action.EXIT,
            TvBack.action(atHome = true, armedAtMs = 5_000L, nowMs = 6_000L)
        )
        assertEquals(
            TvBack.Action.ARM_EXIT,
            TvBack.action(atHome = true, armedAtMs = 5_000L, nowMs = 8_000L)
        )
    }

    @Test
    fun escapeIsABackKey() {
        assertTrue(TvBack.isBackKey(TvBack.KEYCODE_BACK))
        assertTrue(TvBack.isBackKey(TvBack.KEYCODE_ESCAPE))
        assertFalse(TvBack.isBackKey(66))
    }

    @Test
    fun onBackWithoutActivityNavigates() {
        assertEquals(
            TvBack.Action.GO_HOME,
            TvBack.onBack(activity = null, atHome = false, armedAtMs = 0L, nowMs = 1_000L)
        )
        assertEquals(
            TvBack.Action.ARM_EXIT,
            TvBack.onBack(activity = null, atHome = true, armedAtMs = 0L, nowMs = 1_000L)
        )
    }
}
