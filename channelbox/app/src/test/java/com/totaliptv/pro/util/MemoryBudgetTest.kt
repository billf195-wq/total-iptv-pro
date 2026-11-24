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
    fun ramClassTreatsStreamerStyle4GbReportAsFourGig() {
        // 2 GB emulator (~1972 MB)
        assertEquals(2, MemoryBudget.ramClassGb(2018820L * 1024L))
        assertEquals(2, MemoryBudget.ramClassGb(MemoryBudget.TWO_GB_BYTES))
        // ~3 GB
        assertEquals(3, MemoryBudget.ramClassGb(3072L * 1024L * 1024L))
        assertEquals(3, MemoryBudget.ramClassGb(3000L * 1024L * 1024L))
        // Streamer reports ~3.78 GiB on a 4 GB box
        assertEquals(4, MemoryBudget.ramClassGb(3962200L * 1024L))
        assertEquals(4, MemoryBudget.ramClassGb(MemoryBudget.FOUR_GB_BYTES))
    }

    @Test
    fun coilMemoryCacheFollowsRamTiersAndHeapCap() {
        val twoGb = 2018820L * 1024L
        val threeGb = 3072L * 1024L * 1024L
        val streamer = 3962200L * 1024L
        // 2 GB / 192 MB default heap, 512 MB large heap: requested 128 MB fits
        assertEquals(128 * 1024 * 1024, MemoryBudget.coilMemoryCacheBytes(twoGb, heapLimitMb = 512))
        assertEquals(128 * 1024 * 1024, MemoryBudget.coilMemoryCacheBytes(twoGb, heapLimitMb = 192))
        // ~3 GB
        assertEquals(256 * 1024 * 1024, MemoryBudget.coilMemoryCacheBytes(threeGb, heapLimitMb = 512))
        // 4 GB Streamer: 384 MB is 75% of the 512 MB large heap
        assertEquals(384 * 1024 * 1024, MemoryBudget.coilMemoryCacheBytes(streamer, heapLimitMb = 512))
        // Without largeHeap the Streamer's 384 MB class would cap 75% at 288 MB
        assertEquals(288 * 1024 * 1024, MemoryBudget.coilMemoryCacheBytes(streamer, heapLimitMb = 384))
    }

    @Test
    fun coilDiskCacheScalesToFreeSpaceAndCapsAtTwoGb() {
        val twoGb = MemoryBudget.TWO_GB_BYTES
        val mb = 1024L * 1024L
        // Plenty of free space: cap 2 GB (25% of 20 GB is 5 GB, reserve 2 GB, usable 18 GB)
        assertEquals(twoGb, MemoryBudget.coilDiskCacheBytes(20L * twoGb / 2))
        // 20 GiB free
        assertEquals(twoGb, MemoryBudget.coilDiskCacheBytes(20L * 1024L * 1024L * 1024L))
        // 3 GB free: 25% = 768 MB, reserve min(2 GB, 1.5 GB)=1.5 GB, usable=1.5 GB -> 768 MB
        assertEquals(768L * mb * 1024 / 1024, MemoryBudget.coilDiskCacheBytes(3L * 1024L * 1024L * 1024L))
        // 8 GB free: 25% = 2 GB, reserve 2 GB, usable 6 GB -> 2 GB cap
        assertEquals(twoGb, MemoryBudget.coilDiskCacheBytes(8L * 1024L * 1024L * 1024L))
        // Tight volume still gets a 64 MB floor
        assertEquals(64L * 1024L * 1024L, MemoryBudget.coilDiskCacheBytes(100L * 1024L * 1024L))
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
        assertTrue(app.contains("MemoryBudget.coilMemoryCacheBytes(this@TotalIptvProApp)"))
        assertTrue(app.contains("MemoryBudget.coilDiskCacheBytes(dir)"))
    }

    @Test
    fun networkImageDoesNotFlashLetterWhileLoadingFromDisk() {
        val src = java.io.File("src/tv/java/com/totaliptv/pro/ui/components/Components.kt").readText()
        assertTrue(src.contains("placeholderMemoryCacheKey(cacheKey)"))
        assertTrue(src.contains("memoryCacheKey(cacheKey)"))
        assertTrue(src.contains("crossfade(false)"))
        assertTrue(src.contains("if (url.isNullOrBlank() || failed)"))
        val fn = src.substringAfter("fun NetworkImage")
        val body = fn.substringBefore("fun ErrorText")
        assertTrue(body.contains("onError = { failed = true }"))
        assertTrue(body.contains("if (url.isNullOrBlank() || failed)"))
        val desktop = java.io.File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopComponents.kt").readText()
        assertTrue(desktop.contains("placeholderMemoryCacheKey(cacheKey)"))
    }

    @Test
    fun tvManifestRequestsLargeHeap() {
        val man = java.io.File("src/tv/AndroidManifest.xml").readText()
        assertTrue(man.contains("android:largeHeap=\"true\""))
    }
}

