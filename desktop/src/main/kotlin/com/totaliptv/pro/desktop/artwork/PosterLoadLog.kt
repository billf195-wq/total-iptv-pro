package com.totaliptv.pro.desktop.artwork

import com.totaliptv.pro.desktop.player.PlaybackDebugLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One debug line per burst of poster loads (a grid page), not one line per image. */
object PosterLoadLog {
    private const val QUIET_MS = 700L
    private val lock = Any()
    private var burst: Burst? = null
    private var pending: ScheduledFuture<*>? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "poster-load-log").apply { isDaemon = true }
    }

    fun record(page: String, source: String, elapsedMs: Long, width: Int, height: Int) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val current = burst
            val active = if (current == null || now - current.lastMs > 2_000L || current.page != page) {
                current?.let { flushLocked(it) }
                Burst(page = page, firstMs = now, lastMs = now)
            } else {
                current
            }
            active.lastMs = now
            active.count += 1
            when (source) {
                "network" -> active.network += 1
                "disk" -> active.disk += 1
                else -> active.memory += 1
            }
            if (source != "memory") active.fetchMs += elapsedMs
            if (width > 0 && height > 0) {
                active.widthSum += width
                active.heightSum += height
                active.sized += 1
            }
            burst = active
            pending?.cancel(false)
            pending = scheduler.schedule({
                synchronized(lock) {
                    val done = burst ?: return@synchronized
                    burst = null
                    pending = null
                    flushLocked(done)
                }
            }, QUIET_MS, TimeUnit.MILLISECONDS)
        }
    }

    internal fun format(burst: Burst): String {
        val wall = (burst.lastMs - burst.firstMs).coerceAtLeast(0L)
        val fetches = burst.network + burst.disk
        val avg = if (fetches == 0) 0 else burst.fetchMs / fetches
        val avgW = if (burst.sized == 0) 0 else burst.widthSum / burst.sized
        val avgH = if (burst.sized == 0) 0 else burst.heightSum / burst.sized
        val first = burst.network > 0
        return "posters page=${burst.page} firstLoad=$first count=${burst.count} " +
            "network=${burst.network} disk=${burst.disk} memory=${burst.memory} " +
            "wallMs=$wall avgFetchMs=$avg decodedAvg=${avgW}x$avgH"
    }

    private fun flushLocked(burst: Burst) {
        if (burst.count <= 0) return
        PlaybackDebugLog.note(format(burst))
    }

    internal class Burst(
        val page: String,
        val firstMs: Long,
        var lastMs: Long,
        var count: Int = 0,
        var network: Int = 0,
        var disk: Int = 0,
        var memory: Int = 0,
        var fetchMs: Long = 0,
        var widthSum: Int = 0,
        var heightSum: Int = 0,
        var sized: Int = 0
    )
}
