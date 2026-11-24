@file:OptIn(coil.annotation.ExperimentalCoilApi::class)

package com.totaliptv.pro.data

import android.app.Application
import android.util.Log
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.repo.CatalogRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Unique poster and banner URLs from a Movies/Series catalog, first-seen order. */
object PosterPrefetchPlanner {
    const val IDLE_DELAY_MS = 40L
    const val STATUS_FILE = "poster-prefetch-status.json"

    fun urlsFor(items: List<MediaItem>): List<String> {
        val out = LinkedHashSet<String>()
        for (item in items) {
            item.artworkUrl()?.let { out.add(it) }
            item.backdropUrl?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        return out.toList()
    }

    fun catalogItems(repository: CatalogRepository): List<MediaItem> {
        val vod = repository.itemsByKind(ContentKind.VOD)
        val series = repository.itemsByKind(ContentKind.SERIES)
        return vod + series
    }

    /** Same-source cursor; a new source or a shorter list starts at 0. */
    fun resumeIndex(savedSourceId: String?, savedIndex: Int, sourceId: String, total: Int): Int {
        if (savedSourceId != sourceId || total <= 0) return 0
        return savedIndex.coerceIn(0, total)
    }
}

data class PosterPrefetchStats(
    val sourceId: String,
    val total: Int,
    val done: Int,
    val skipped: Int,
    val failed: Int,
    val diskBytes: Long,
    val running: Boolean,
    val pausedForPlayback: Boolean,
    val cursor: Int,
)

/**
 * Walks [urls] on a background dispatcher: skip hits already on disk, fetch the
 * rest into the disk cache only, pause while [isPlaybackActive], and throttle
 * between downloads so this never competes with the UI or a stream.
 */
class PosterPrefetchRunner(
    private val isPlaybackActive: () -> Boolean,
    private val awaitPlaybackIdle: suspend () -> Unit,
    private val isCached: (String) -> Boolean,
    private val fetchToDisk: suspend (String) -> Boolean,
    private val diskBytes: () -> Long,
    private val onStats: (PosterPrefetchStats) -> Unit = {},
    private val idleDelayMs: Long = PosterPrefetchPlanner.IDLE_DELAY_MS,
) {
    suspend fun run(
        urls: List<String>,
        sourceId: String,
        startIndex: Int = 0,
    ): PosterPrefetchStats {
        var done = 0
        var skipped = 0
        var failed = 0
        val total = urls.size
        fun emit(running: Boolean, paused: Boolean, index: Int) {
            onStats(
                PosterPrefetchStats(
                    sourceId = sourceId,
                    total = total,
                    done = done,
                    skipped = skipped,
                    failed = failed,
                    diskBytes = diskBytes(),
                    running = running,
                    pausedForPlayback = paused,
                    cursor = index,
                )
            )
        }
        val start = startIndex.coerceIn(0, total)
        emit(running = true, paused = false, index = start)
        var index = start
        while (index < total) {
            if (isPlaybackActive()) {
                emit(running = true, paused = true, index = index)
                awaitPlaybackIdle()
            }
            val url = urls[index]
            when {
                isCached(url) -> skipped++
                fetchToDisk(url) -> done++
                else -> failed++
            }
            index++
            emit(running = true, paused = false, index = index)
            if (index < total && idleDelayMs > 0) delay(idleDelayMs)
        }
        val final = PosterPrefetchStats(
            sourceId = sourceId,
            total = total,
            done = done,
            skipped = skipped,
            failed = failed,
            diskBytes = diskBytes(),
            running = false,
            pausedForPlayback = false,
            cursor = total,
        )
        onStats(final)
        return final
    }
}

/**
 * After Movies and Series lists have finished loading, downloads every poster
 * and backdrop into Coil's disk image cache on a background thread. Already
 * cached URLs are skipped so a restart resumes where it left off. Playback
 * pauses the queue. Nothing is written into the memory cache.
 */
internal object PosterPrefetchPolicy {
    /** Cancel an in-flight prefetch only when Movies/Series lists are downloading. */
    fun shouldCancel(loading: Boolean): Boolean = loading

    /**
     * Start a new prefetch only when lists are idle and no job is already
     * running. A catalogRevision bump must not cancel a download in progress.
     */
    fun shouldLaunch(loading: Boolean, jobActive: Boolean): Boolean = !loading && !jobActive
}

object PosterPrefetch {
    private const val TAG = "PosterPrefetch"
    private val started = AtomicBoolean(false)
    private var job: Job? = null

    fun start(app: Application, repository: CatalogRepository, scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(repository.vodLoading, repository.catalogRevision) { loading, rev ->
                loading to rev
            }.collect { (loading, _) ->
                if (PosterPrefetchPolicy.shouldCancel(loading)) {
                    // Never compete with the list download.
                    job?.cancel()
                    job = null
                    return@collect
                }
                if (!PosterPrefetchPolicy.shouldLaunch(loading, job?.isActive == true)) {
                    return@collect
                }
                job = scope.launch(Dispatchers.IO) {
                    runCatching { runOnce(app, repository) }
                        .onFailure { err ->
                            if (err is kotlinx.coroutines.CancellationException) throw err
                            Log.w(TAG, "prefetch failed: ${err.javaClass.simpleName}")
                        }
                }
            }
        }
    }

    internal fun writeStatus(file: File, stats: PosterPrefetchStats) {
        val json = buildString {
            append("{")
            append("\"sourceId\":\"").append(stats.sourceId.replace("\"", "")).append("\",")
            append("\"total\":").append(stats.total).append(",")
            append("\"done\":").append(stats.done).append(",")
            append("\"skipped\":").append(stats.skipped).append(",")
            append("\"failed\":").append(stats.failed).append(",")
            append("\"diskBytes\":").append(stats.diskBytes).append(",")
            append("\"running\":").append(stats.running).append(",")
            append("\"pausedForPlayback\":").append(stats.pausedForPlayback).append(",")
            append("\"cursor\":").append(stats.cursor)
            append("}")
        }
        file.writeText(json)
    }

    private suspend fun runOnce(app: Application, repository: CatalogRepository) {
        val items = PosterPrefetchPlanner.catalogItems(repository)
        if (items.isEmpty()) return
        val urls = PosterPrefetchPlanner.urlsFor(items)
        if (urls.isEmpty()) return
        val sourceId = "lib-${items.size}-${urls.size}"
        val loader = app.imageLoader
        val disk = loader.diskCache
        val statusFile = File(app.filesDir, PosterPrefetchPlanner.STATUS_FILE)
        Log.i(TAG, "start total=${urls.size} diskBytes=${disk?.size ?: 0} max=${disk?.maxSize ?: 0}")
        val runner = PosterPrefetchRunner(
            isPlaybackActive = { PlaybackGate.active.value },
            awaitPlaybackIdle = { PlaybackGate.active.first { playing -> !playing } },
            isCached = { url -> disk?.openSnapshot(url)?.use { true } ?: false },
            fetchToDisk = { url ->
                val req = ImageRequest.Builder(app)
                    .data(url)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    // Decode a 1x1 bitmap only; the disk cache still stores the original file.
                    .size(Size(1, 1))
                    .addHeader("User-Agent", LogoUrls.USER_AGENT)
                    .allowHardware(false)
                    .build()
                loader.execute(req) is SuccessResult
            },
            diskBytes = { disk?.size ?: 0L },
            onStats = { stats ->
                if (stats.cursor == stats.total || stats.cursor % 25 == 0 || stats.pausedForPlayback) {
                    Log.i(
                        TAG,
                        "progress done=${stats.done} skipped=${stats.skipped} failed=${stats.failed} " +
                            "total=${stats.total} diskBytes=${stats.diskBytes} " +
                            "paused=${stats.pausedForPlayback} running=${stats.running}"
                    )
                    runCatching { writeStatus(statusFile, stats) }
                }
            },
        )
        val stats = runner.run(urls, sourceId, startIndex = 0)
        Log.i(
            TAG,
            "finished done=${stats.done} skipped=${stats.skipped} failed=${stats.failed} " +
                "total=${stats.total} diskBytes=${stats.diskBytes}"
        )
        runCatching { writeStatus(statusFile, stats) }
    }

    /** Test-only: allow a second start() in the same process. */
    internal fun resetForTest() {
        started.set(false)
        job?.cancel()
        job = null
    }
}
