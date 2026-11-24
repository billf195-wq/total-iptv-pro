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
    fun persistScaleDownOnlyWhenTight() {
        assertEquals(8, MemoryBudget.persistEveryPages(tight = true))
        assertEquals(5, MemoryBudget.persistEveryPages(tight = false))
        assertEquals(4, MemoryBudget.serviceIndexApplyEveryPersists(tight = true))
        assertEquals(2, MemoryBudget.serviceIndexApplyEveryPersists(tight = false))
    }

    @Test
    fun coilImageCachesAreGenerousOnEveryBox() {
        assertEquals(64 * 1024 * 1024, MemoryBudget.coilMemoryCacheBytes())
        assertEquals(512L * 1024 * 1024, MemoryBudget.coilDiskCacheBytes())
    }

    @Test
    fun neverEvictsGridBitmapsEvenOnTwoGbOrLowRam() {
        val twoGb = MemoryBudget.TWO_GB_BYTES
        assertFalse(MemoryBudget.shouldEvictGridBitmaps(totalMemBytes = twoGb, isLowRam = false))
        assertFalse(MemoryBudget.shouldEvictGridBitmaps(totalMemBytes = twoGb - 1, isLowRam = false))
        assertFalse(MemoryBudget.shouldEvictGridBitmaps(totalMemBytes = 1024L * 1024L * 1024L, isLowRam = false))
        assertFalse(MemoryBudget.shouldEvictGridBitmaps(totalMemBytes = twoGb + 1, isLowRam = true))
        assertFalse(MemoryBudget.shouldEvictGridBitmaps(totalMemBytes = 0L, isLowRam = false))
    }

    @Test
    fun homeDoesNotClearImageMemoryCacheOnCategorySwitch() {
        val home = java.io.File("src/tv/java/com/totaliptv/pro/ui/home/HomeScreen.kt").readText()
        assertFalse(home.contains("memoryCache?.clear()"))
        assertFalse(home.contains("shouldEvictGridBitmaps"))
        val app = java.io.File("src/main/java/com/totaliptv/pro/TotalIptvProApp.kt").readText()
        assertTrue(app.contains("cacheDir.resolve(\"image_cache\")"))
        assertTrue(app.contains("respectCacheHeaders(false)"))
    }
}
