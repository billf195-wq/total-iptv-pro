package com.totaliptv.pro.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryBudgetTest {
    @Test
    fun thirtyTwoBitIsAlwaysTight() {
        assertTrue(MemoryBudget.tight(is64Bit = false, isLowRam = false, memoryClassMb = 256))
        assertTrue(MemoryBudget.tight(is64Bit = false, isLowRam = true, memoryClassMb = 48))
    }

    @Test
    fun sixtyFourBitStaysOpenUnlessLowRamOrSmallHeap() {
        assertFalse(MemoryBudget.tight(is64Bit = true, isLowRam = false, memoryClassMb = 256))
        assertFalse(MemoryBudget.tight(is64Bit = true, isLowRam = false, memoryClassMb = 384))
        assertTrue(MemoryBudget.tight(is64Bit = true, isLowRam = true, memoryClassMb = 256))
        assertTrue(MemoryBudget.tight(is64Bit = true, isLowRam = false, memoryClassMb = 128))
        assertFalse(MemoryBudget.tight(is64Bit = true, isLowRam = false, memoryClassMb = 0))
    }

    @Test
    fun coilAndPersistScaleDownOnlyWhenTight() {
        assertEquals(8 * 1024 * 1024, MemoryBudget.coilMemoryCacheBytes(tight = true))
        assertEquals(32 * 1024 * 1024, MemoryBudget.coilMemoryCacheBytes(tight = false))
        assertEquals(32L * 1024 * 1024, MemoryBudget.coilDiskCacheBytes(tight = true))
        assertEquals(64L * 1024 * 1024, MemoryBudget.coilDiskCacheBytes(tight = false))
        assertEquals(8, MemoryBudget.persistEveryPages(tight = true))
        assertEquals(5, MemoryBudget.persistEveryPages(tight = false))
        assertEquals(4, MemoryBudget.serviceIndexApplyEveryPersists(tight = true))
        assertEquals(2, MemoryBudget.serviceIndexApplyEveryPersists(tight = false))
    }

    @Test
    fun evictsGridBitmapsOnlyOnTwoGbOrLowRam() {
        val twoGb = MemoryBudget.TWO_GB_BYTES
        assertTrue(MemoryBudget.shouldEvictGridBitmaps(totalMemBytes = twoGb, isLowRam = false))
        assertTrue(MemoryBudget.shouldEvictGridBitmaps(totalMemBytes = twoGb - 1, isLowRam = false))
        assertTrue(MemoryBudget.shouldEvictGridBitmaps(totalMemBytes = 1024L * 1024L * 1024L, isLowRam = false))
        assertFalse(MemoryBudget.shouldEvictGridBitmaps(totalMemBytes = twoGb + 1, isLowRam = false))
        assertTrue(MemoryBudget.shouldEvictGridBitmaps(totalMemBytes = twoGb + 1, isLowRam = true))
        assertFalse(MemoryBudget.shouldEvictGridBitmaps(totalMemBytes = 0L, isLowRam = false))
    }
}
