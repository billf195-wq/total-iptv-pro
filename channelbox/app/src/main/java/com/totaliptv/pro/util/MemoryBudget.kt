package com.totaliptv.pro.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import kotlin.math.min

/**
 * Runtime memory budget for 32-bit and low-RAM TV boxes.
 * 64-bit devices keep the larger caches unless they are flagged low-RAM
 * or given a very small heap.
 */
object MemoryBudget {
    data class Snapshot(
        val is64Bit: Boolean,
        val isLowRam: Boolean,
        val memoryClassMb: Int,
        val tight: Boolean,
    )

    fun tight(is64Bit: Boolean, isLowRam: Boolean, memoryClassMb: Int): Boolean =
        !is64Bit || isLowRam || memoryClassMb in 1..192

    fun snapshot(context: Context): Snapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val is64 = is64BitProcess()
        val lowRam = am.isLowRamDevice
        val memClass = am.memoryClass
        return Snapshot(is64, lowRam, memClass, tight(is64, lowRam, memClass))
    }

    fun is64BitProcess(): Boolean =
        if (Build.VERSION.SDK_INT >= 23) Process.is64Bit()
        else Build.SUPPORTED_ABIS.any { it.contains("64") }

    const val TWO_GB_BYTES = 2L * 1024L * 1024L * 1024L
    const val FOUR_GB_BYTES = 4L * 1024L * 1024L * 1024L

    /** 1.4.119 memory-cache tiers: <=2 GB, ~3 GB, >=4 GB (4 GB boxes that report ~3.8 GB included). */
    const val MEMORY_CACHE_2GB_BYTES = 128 * 1024 * 1024
    const val MEMORY_CACHE_3GB_BYTES = 256 * 1024 * 1024
    const val MEMORY_CACHE_4GB_BYTES = 384 * 1024 * 1024
    const val DISK_CACHE_MAX_BYTES = 2L * 1024L * 1024L * 1024L

    /**
     * Never let the image memory cache exceed this share of the per-app heap
     * limit (largeMemoryClass when largeHeap is on). 384 MB is 75% of the
     * Streamer's 512 MB large heap, which is the requested 4 GB tier.
     */
    const val HEAP_CACHE_NUMERATOR = 3
    const val HEAP_CACHE_DENOMINATOR = 4

    /**
     * Device RAM class used to pick a cache tier. Real 4 GB boxes often report
     * a little under 4 GiB (the Streamer reports about 3.78 GiB) so the 4 GB
     * tier starts at 3.5 GiB. The 3 GB tier starts at 2.5 GiB.
     */
    fun ramClassGb(totalMemBytes: Long): Int {
        val mb = totalMemBytes / (1024L * 1024L)
        return when {
            mb < 2560L -> 2
            mb < 3584L -> 3
            else -> 4
        }
    }

    fun memoryCacheTierBytes(totalMemBytes: Long): Int = when (ramClassGb(totalMemBytes)) {
        2 -> MEMORY_CACHE_2GB_BYTES
        3 -> MEMORY_CACHE_3GB_BYTES
        else -> MEMORY_CACHE_4GB_BYTES
    }

    /**
     * Requested tier, capped at a safe share of the per-app heap limit in MB.
     */
    fun coilMemoryCacheBytes(totalMemBytes: Long, heapLimitMb: Int): Int {
        val tier = memoryCacheTierBytes(totalMemBytes)
        val safe = ((heapLimitMb.toLong() * 1024L * 1024L * HEAP_CACHE_NUMERATOR) / HEAP_CACHE_DENOMINATOR)
            .toInt()
            .coerceAtLeast(32 * 1024 * 1024)
        return min(tier, safe)
    }

    fun coilMemoryCacheBytes(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        // largeHeap is set on the TV manifest so the process may use largeMemoryClass.
        return coilMemoryCacheBytes(info.totalMem, am.largeMemoryClass)
    }

    /**
     * Disk cache: min(2 GB, 25% of free space, free space minus a reserve).
     * The reserve is min(2 GB, half of free space) so short volumes still
     * keep plenty of room. Floor is 64 MB so a tight disk still caches something.
     */
    fun coilDiskCacheBytes(freeSpaceBytes: Long): Long {
        val leave = min(TWO_GB_BYTES, (freeSpaceBytes / 2).coerceAtLeast(0L))
        val usable = (freeSpaceBytes - leave).coerceAtLeast(0L)
        val fraction = (freeSpaceBytes / 4).coerceAtLeast(0L)
        return minOf(DISK_CACHE_MAX_BYTES, usable, fraction).coerceAtLeast(64L * 1024L * 1024L)
    }

    fun coilDiskCacheBytes(cacheDir: File): Long = coilDiskCacheBytes(cacheDir.usableSpace)

    /** Write the service-cache JSON every N TMDB pages (plus at query end). */
    fun persistEveryPages(tight: Boolean): Int = if (tight) 8 else 5

    /** Rebuild ServiceIndex every N persist callbacks (plus first and end). */
    fun serviceIndexApplyEveryPersists(tight: Boolean): Int = if (tight) 4 else 2

    /**
     * Never evict decoded grid or banner bitmaps when switching
     * categories, including on 2 GB / low-RAM boxes.
     */
    fun shouldEvictGridBitmaps(totalMemBytes: Long, isLowRam: Boolean): Boolean = false

    fun shouldEvictGridBitmaps(context: Context): Boolean = false
}
