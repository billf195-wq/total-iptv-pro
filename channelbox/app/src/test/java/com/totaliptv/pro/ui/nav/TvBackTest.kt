package com.totaliptv.pro.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
