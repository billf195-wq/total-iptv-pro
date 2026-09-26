package com.totaliptv.pro.ui.epg

import com.totaliptv.pro.data.model.EpgChannelRow
import com.totaliptv.pro.data.model.EpgProgram
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/**
 * The guide used to queue every channel in the category after the visible
 * rows, which fired thousands of get_short_epg calls on a large lineup.
 * Only the rows on screen, plus a short buffer, are fetched.
 */
object GuideEpgLoad {
    /** In-flight cap. A 4,000-channel lineup must not open more than this. */
    const val PARALLEL = 3

    /** Extra rows past the viewport so moving down is already filled. */
    const val BUFFER_ROWS = 4

    /** Reopening the guide within this window reuses listings. */
    const val CACHE_TTL_MS = 30L * 60L * 1000L

    /** How long to wait after the last scroll move before fetching. */
    const val SCROLL_DEBOUNCE_MS = 400L

    fun fetchOrder(
        channelIds: List<String>,
        firstVisibleIndex: Int,
        visibleCount: Int,
        focusedId: String?,
        alreadyStarted: Set<String>,
        buffer: Int = BUFFER_ROWS
    ): List<Int> {
        if (channelIds.isEmpty()) return emptyList()
        val last = channelIds.lastIndex
        val start = firstVisibleIndex.coerceIn(0, last)
        val span = visibleCount.coerceAtLeast(1) + buffer.coerceAtLeast(0)
        val visibleEnd = (start + span).coerceAtMost(channelIds.size)
        val focusedIdx = focusedId?.let { id -> channelIds.indexOf(id) }?.takeIf { it in start until visibleEnd }
        return buildList {
            focusedIdx?.let { add(it) }
            addAll(start until visibleEnd)
        }.distinct().filter { idx -> channelIds[idx] !in alreadyStarted }
    }

    fun nextBatch(
        channelIds: List<String>,
        firstVisibleIndex: Int,
        visibleCount: Int,
        focusedId: String?,
        alreadyStarted: Set<String>,
        limit: Int = PARALLEL
    ): List<Int> = fetchOrder(
        channelIds, firstVisibleIndex, visibleCount, focusedId, alreadyStarted
    ).take(limit)

    fun applyRow(rows: List<EpgChannelRow>, filled: EpgChannelRow): List<EpgChannelRow> =
        rows.map { if (it.channel.id == filled.channel.id) filled else it }

    fun cacheFresh(cachedAtMs: Long, nowMs: Long, ttlMs: Long = CACHE_TTL_MS): Boolean =
        cachedAtMs > 0L && nowMs - cachedAtMs < ttlMs
}

/**
 * Moves a time cursor across programs and keeps Up/Down on the same column.
 */
object GuideCursor {
    fun programAt(programs: List<EpgProgram>, cursorMs: Long): EpgProgram? {
        if (programs.isEmpty()) return null
        return programs.firstOrNull { cursorMs in it.startMs until it.endMs }
            ?: programs.minByOrNull { kotlin.math.abs(it.startMs - cursorMs) }
    }

    fun step(programs: List<EpgProgram>, cursorMs: Long, direction: Int, windowStart: Long, windowEnd: Long): Long {
        val ordered = programs.filter { it.endMs > windowStart && it.startMs < windowEnd }.sortedBy { it.startMs }
        val hour = 60L * 60L * 1000L
        if (ordered.isEmpty()) {
            return (cursorMs + direction.coerceIn(-1, 1) * hour).coerceIn(windowStart, (windowEnd - 1).coerceAtLeast(windowStart))
        }
        val idx = ordered.indexOfLast { it.startMs <= cursorMs }.let { if (it < 0) 0 else it }
        val next = (idx + direction.coerceIn(-1, 1)).coerceIn(0, ordered.lastIndex)
        return ordered[next].startMs.coerceIn(windowStart, (windowEnd - 1).coerceAtLeast(windowStart))
    }

    fun scrollOffsetPx(programStartMs: Long, windowStartMs: Long, windowMs: Long, totalWidthPx: Int): Int {
        if (windowMs <= 0L || totalWidthPx <= 0) return 0
        val frac = (programStartMs - windowStartMs).toFloat() / windowMs.toFloat()
        return (totalWidthPx * frac).toInt().coerceIn(0, totalWidthPx)
    }
}

/**
 * Caps concurrent EPG calls and pauses the whole guide after HTTP 429.
 */
class EpgRequestGate(
    val maxInFlight: Int = GuideEpgLoad.PARALLEL,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sleeper: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutex = Mutex()
    private var inFlight = 0
    private var pauseUntilMs = 0L
    private var backoffMs = BACKOFF_START_MS

    var peakInFlight: Int = 0
        private set
    var started: Int = 0
        private set

    suspend fun acquire() {
        while (true) {
            val waitMs = mutex.withLock {
                val pause = pauseUntilMs - now()
                when {
                    pause > 0L -> pause
                    inFlight >= maxInFlight -> 40L
                    else -> {
                        inFlight++
                        started++
                        peakInFlight = max(peakInFlight, inFlight)
                        0L
                    }
                }
            }
            if (waitMs == 0L) return
            sleeper(waitMs)
        }
    }

    suspend fun release() {
        mutex.withLock {
            if (inFlight > 0) inFlight--
        }
    }

    suspend fun onRateLimited() {
        mutex.withLock {
            val stamp = now()
            pauseUntilMs = stamp + backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
        }
    }

    suspend fun onSuccess() {
        mutex.withLock { backoffMs = BACKOFF_START_MS }
    }

    companion object {
        const val BACKOFF_START_MS = 2_000L
        const val BACKOFF_MAX_MS = 60_000L
    }
}
