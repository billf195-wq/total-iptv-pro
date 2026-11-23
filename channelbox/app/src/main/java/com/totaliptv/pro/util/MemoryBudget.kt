package com.totaliptv.pro.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process

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

    fun coilMemoryCacheBytes(tight: Boolean): Int =
        if (tight) 8 * 1024 * 1024 else 32 * 1024 * 1024

    fun coilDiskCacheBytes(tight: Boolean): Long =
        if (tight) 32L * 1024 * 1024 else 64L * 1024 * 1024

    /** Write the service-cache JSON every N TMDB pages (plus at query end). */
    fun persistEveryPages(tight: Boolean): Int = if (tight) 8 else 5

    /** Rebuild ServiceIndex every N persist callbacks (plus first and end). */
    fun serviceIndexApplyEveryPersists(tight: Boolean): Int = if (tight) 4 else 2

    const val TWO_GB_BYTES = 2L * 1024L * 1024L * 1024L

    /**
     * Evict decoded Movies/Series grid bitmaps only on low-RAM boxes
     * (isLowRamDevice or total RAM of 2 GB or less).
     */
    fun shouldEvictGridBitmaps(totalMemBytes: Long, isLowRam: Boolean): Boolean =
        isLowRam || (totalMemBytes in 1L..TWO_GB_BYTES)

    fun shouldEvictGridBitmaps(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.isLowRamDevice) return true
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return shouldEvictGridBitmaps(info.totalMem, isLowRam = false)
    }
}
