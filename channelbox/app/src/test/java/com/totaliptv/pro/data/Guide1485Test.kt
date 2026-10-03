package com.totaliptv.pro.data

import com.totaliptv.pro.ui.player.PlayerOverlayTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Guide1485Test {
    @Test
    fun staleBulkFileStaysUsableForHours() {
        val f = File.createTempFile("guide", ".tsv")
        try {
            f.writeText("# tip-guide-epg 1\n1\t1\t2\tx\n")
            val now = f.lastModified() + 8L * 60L * 60L * 1000L
            assertFalse(GuideBulkCache.isFresh(f, now))
            assertTrue(GuideBulkCache.isUsable(f, now))
            assertFalse(GuideBulkCache.isUsable(f, f.lastModified() + GuideBulkCache.USABLE_MS + 1))
        } finally {
            f.delete()
        }
    }

    @Test
    fun bannersAreShorter() {
        assertEquals(3_000L, PlayerOverlayTiming.holdMs(10_000L, 0L))
        assertEquals(3_000L, PlayerOverlayTiming.holdMs(10_000L, 9_500L))
        assertTrue(PlayerOverlayTiming.SPLIT_CONTROLS_MS <= 3_000L)
    }
}
