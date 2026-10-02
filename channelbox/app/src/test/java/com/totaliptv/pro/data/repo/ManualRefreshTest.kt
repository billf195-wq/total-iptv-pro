package com.totaliptv.pro.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRefreshTest {
    @Test
    fun beginAndEndTrackActiveReloadsAndBumpEpoch() {
        val epoch0 = ManualRefresh.epoch.value
        val active0 = ManualRefresh.active.value
        ManualRefresh.begin()
        assertEquals(epoch0 + 1, ManualRefresh.epoch.value)
        assertEquals(active0 + 1, ManualRefresh.active.value)
        ManualRefresh.end()
        assertEquals(active0, ManualRefresh.active.value)
        ManualRefresh.end()
        assertTrue(ManualRefresh.active.value >= 0)
    }

    @Test
    fun requestHomeBumpsCounter() {
        val h = ManualRefresh.homeRequests.value
        ManualRefresh.requestHome()
        assertEquals(h + 1, ManualRefresh.homeRequests.value)
    }
}
