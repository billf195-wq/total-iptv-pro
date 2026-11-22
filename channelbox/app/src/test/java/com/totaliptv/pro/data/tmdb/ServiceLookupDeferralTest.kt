package com.totaliptv.pro.data.tmdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceLookupDeferralTest {
    @Test
    fun refreshTappedDuringPlaybackResumesForced() {
        val d = ServiceLookupDeferral()
        assertNull(d.consume())
        d.mark(forceRequested = true)
        assertTrue(d.isDeferred())
        assertTrue(d.forcePending())
        assertEquals(ServiceLookupDeferral.PAUSED_FORCED, d.pausedStatus(forceRequested = true))
        // Later automatic ticks must not clear the forced flag.
        d.mark(forceRequested = false)
        assertEquals(true, d.consume())
        assertFalse(d.isDeferred())
        assertFalse(d.forcePending())
        assertNull(d.consume())
    }

    @Test
    fun backgroundLookupPausedDuringPlaybackResumesUnforced() {
        val d = ServiceLookupDeferral()
        d.mark(forceRequested = false)
        assertEquals(ServiceLookupDeferral.PAUSED, d.pausedStatus(forceRequested = false))
        assertEquals(false, d.consume())
    }
}
