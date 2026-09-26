package com.totaliptv.pro.desktop.artwork

import com.totaliptv.pro.desktop.data.ContentKind
import com.totaliptv.pro.desktop.data.MediaItem
import com.totaliptv.pro.desktop.util.AppPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import com.totaliptv.pro.desktop.player.PlaybackDebugLog
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Disk-cached TMDB vote_average values. Visible items jump the queue.
 * A Home backfill walks the rest slowly so Top rated can settle without blocking the page.
 */
object TmdbRatingStore {
    const val MAX_IN_FLIGHT = 5
    const val START_GAP_MS = 300L

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val lock = Any()
    private val memory = HashMap<String, TmdbRating>()
    private val queued = HashSet<String>()
    private val queue = ArrayDeque<Pending>()
    private var loaded = false
    private val revisionState = MutableStateFlow(0)
    val revision: StateFlow<Int> = revisionState

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tmdb-ratings").apply { isDaemon = true }
    }
    @Volatile
    private var draining = false

    internal var fetch: (TmdbRequest) -> String? = { TmdbClient.get(it) }
    internal var nowMs: () -> Long = { System.currentTimeMillis() }
    internal var fileOverride: Path? = null
    internal var pauseMs: Long = 0
    internal var autoDrain: Boolean = true
    /** Title searches run this many at a time. Visible rows are still first in the queue. */
    internal var maxInFlight: Int = MAX_IN_FLIGHT
    /**
     * Gap between request starts. Five in flight with a 300ms gap stays near 3/s,
     * under TMDB's 40-per-10-seconds window and well under 40 per second.
     */
    internal var startGapMs: Long = START_GAP_MS
    internal var parallel: Boolean = true
    internal var log: (String) -> Unit = { PlaybackDebugLog.note(it) }

    private val ticks = AtomicInteger(0)
    private val pool by lazy {
        Executors.newFixedThreadPool(MAX_IN_FLIGHT.coerceAtLeast(1)) { runnable ->
            Thread(runnable, "tmdb-ratings-fetch").apply { isDaemon = true }
        }
    }

    fun displayScore(item: MediaItem): Double {
        ensureLoaded()
        return TmdbRatings.displayScore(TmdbRatings.providerScore(item), peek(item), ArtworkSettings.ratingsActive())
    }

    fun rankScore(item: MediaItem): Double {
        ensureLoaded()
        return TmdbRatings.rankScore(TmdbRatings.providerScore(item), peek(item), ArtworkSettings.ratingsActive())
    }

    fun displayScore(kind: ContentKind, tmdbId: String?, providerRating: String?, rating5Based: Double? = null): Double {
        ensureLoaded()
        val provider = TmdbRatings.providerScore(providerRating, rating5Based)
        val tmdb = tmdbId?.let { peekId(kind, it) }
        return TmdbRatings.displayScore(provider, tmdb, ArtworkSettings.ratingsActive())
    }

    fun enqueue(items: List<MediaItem>, front: Boolean) {
        if (!ArtworkSettings.ratingsActive()) return
        ensureLoaded()
        val key = ArtworkSettings.tmdbApiKey
        if (key.isBlank()) return
        val now = nowMs()
        synchronized(lock) {
            val pending = items.mapNotNull { item ->
                if (item.kind != ContentKind.VOD && item.kind != ContentKind.SERIES) return@mapNotNull null
                val id = item.tmdbId?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
                if (id != null) {
                    val cacheKey = TmdbRatings.cacheKey(item.kind, id)
                    val cached = memory[cacheKey] ?: alternate(item.kind, id)
                    if (cached != null && TmdbRatings.isFresh(cached, now)) return@mapNotNull null
                    if (!queued.add(cacheKey)) {
                        if (front) moveToFront(cacheKey)
                        return@mapNotNull null
                    }
                    Pending(item.kind, id, cacheKey, title = null, year = null)
                } else {
                    val query = TmdbTitle.query(item) ?: return@mapNotNull null
                    val cacheKey = TmdbTitle.lookupKey(query)
                    val cached = memory[cacheKey]
                    if (cached != null && TmdbRatings.isFresh(cached, now)) return@mapNotNull null
                    if (!queued.add(cacheKey)) {
                        if (front) moveToFront(cacheKey)
                        return@mapNotNull null
                    }
                    Pending(item.kind, tmdbId = null, cacheKey, query.title, query.year)
                }
            }
            if (pending.isEmpty()) return
            if (front) pending.asReversed().forEach { queue.addFirst(it) } else pending.forEach { queue.addLast(it) }
        }
        if (autoDrain) kick()
    }

    internal fun resetForTests() {
        autoDrain = false
        parallel = false
        pauseMs = 0
        startGapMs = 0
        log = {}
        synchronized(lock) {
            memory.clear()
            queued.clear()
            queue.clear()
            loaded = false
        }
        ticks.set(0)
        revisionState.value = 0
    }

    internal fun drainForTests() {
        drain()
    }

    internal fun queuedIdsForTests(): List<String> = synchronized(lock) { queue.mapNotNull { it.tmdbId } }

    fun peek(item: MediaItem): TmdbRating? {
        ensureLoaded()
        val id = item.tmdbId?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
        if (id != null) return peekId(item.kind, id)
        val query = TmdbTitle.query(item) ?: return null
        synchronized(lock) {
            return memory[TmdbTitle.lookupKey(query)]
        }
    }

    private fun peekId(kind: ContentKind, id: String): TmdbRating? {
        synchronized(lock) {
            return memory[TmdbRatings.cacheKey(kind, id)] ?: alternate(kind, id)
        }
    }

    private fun alternate(kind: ContentKind, id: String): TmdbRating? {
        val other = if (kind == ContentKind.SERIES) ContentKind.VOD else ContentKind.SERIES
        return memory[TmdbRatings.cacheKey(other, id)]
    }

    private fun kick() {
        if (draining) return
        draining = true
        worker.execute {
            try {
                if (parallel) drainConcurrent() else drain()
            } finally {
                draining = false
                val more = synchronized(lock) { queue.isNotEmpty() }
                if (more) kick()
            }
        }
    }

    private fun takeBatch(): List<Pending> = synchronized(lock) {
        if (queue.isEmpty()) emptyList() else List(minOf(maxInFlight.coerceAtLeast(1), queue.size)) { queue.removeFirst() }
    }

    private fun drain() {
        val apiKey = ArtworkSettings.tmdbApiKey
        while (true) {
            val batch = takeBatch()
            if (batch.isEmpty()) return
            val started = System.nanoTime()
            var resolved = 0
            var missed = 0
            var changed = false
            for (job in batch) {
                val rating = fetchJob(apiKey, job)
                synchronized(lock) {
                    queued.remove(job.cacheKey)
                    if (rating != null) {
                        putRating(job, rating)
                        changed = true
                        if (rating.found) resolved += 1 else missed += 1
                    }
                }
                if (rating != null) bump()
            }
            if (changed) persist()
            log(TmdbRatings.batchLine(resolved, missed, elapsedMs(started)))
            if (pauseMs > 0) Thread.sleep(pauseMs)
        }
    }

    private fun drainConcurrent() {
        val apiKey = ArtworkSettings.tmdbApiKey
        while (true) {
            val batch = takeBatch()
            if (batch.isEmpty()) return
            val started = System.nanoTime()
            val resolved = AtomicInteger(0)
            val missed = AtomicInteger(0)
            val latch = CountDownLatch(batch.size)
            var nextStart = 0L
            for (job in batch) {
                val wait = nextStart - System.currentTimeMillis()
                if (startGapMs > 0 && wait > 0) Thread.sleep(wait)
                nextStart = System.currentTimeMillis() + startGapMs
                pool.execute {
                    try {
                        val rating = fetchJob(apiKey, job)
                        val stored = synchronized(lock) {
                            queued.remove(job.cacheKey)
                            if (rating != null) {
                                putRating(job, rating)
                                if (rating.found) resolved.incrementAndGet() else missed.incrementAndGet()
                                true
                            } else {
                                false
                            }
                        }
                        if (stored) bump()
                    } catch (_: Exception) {
                        synchronized(lock) { queued.remove(job.cacheKey) }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
            persist()
            log(TmdbRatings.batchLine(resolved.get(), missed.get(), elapsedMs(started)))
        }
    }

    private fun bump() {
        revisionState.value = ticks.incrementAndGet()
    }

    private fun elapsedMs(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)

    private fun putRating(job: Pending, rating: TmdbRating) {
        memory[job.cacheKey] = rating
        val id = rating.tmdbId?.takeIf { it.isNotBlank() } ?: job.tmdbId?.takeIf { it.isNotBlank() }
        if (id != null && rating.found) {
            val idKey = TmdbRatings.cacheKey(job.kind, id)
            if (idKey != job.cacheKey) memory[idKey] = rating
        }
    }

    private fun moveToFront(cacheKey: String) {
        val existing = queue.firstOrNull { it.cacheKey == cacheKey } ?: return
        queue.remove(existing)
        queue.addFirst(existing)
    }

    private fun fetchJob(apiKey: String, job: Pending): TmdbRating? {
        val id = job.tmdbId?.takeIf { it.isNotBlank() }
        return if (id != null) fetchRating(apiKey, job) else fetchByTitle(apiKey, job)
    }

    private fun fetchByTitle(apiKey: String, job: Pending): TmdbRating? {
        val title = job.title ?: return null
        val year = job.year ?: return null
        val query = TmdbTitle.Query(title, year, job.kind)
        val now = nowMs()
        val body = try {
            fetch(TmdbRequest(TmdbTitle.searchPath(query), apiKey))
        } catch (_: Exception) {
            null
        } ?: return null
        if (TmdbRatings.isHardFailure(body)) return null
        val match = TmdbTitle.bestMatch(body, query)
        if (match != null) {
            return TmdbRating(match.average, match.votes, found = true, fetchedAtMs = now, tmdbId = match.tmdbId)
        }
        return if (TmdbTitle.isSearchBody(body) || TmdbRatings.isNotFound(body)) {
            TmdbRating(0.0, 0, found = false, fetchedAtMs = now)
        } else {
            null
        }
    }

    private fun fetchRating(apiKey: String, job: Pending): TmdbRating? {
        val id = job.tmdbId?.takeIf { it.isNotBlank() } ?: return null
        val now = nowMs()
        var confirmedMiss = false
        for (path in TmdbRatings.endpoints(job.kind, id)) {
            val body = try {
                fetch(TmdbRequest(path, apiKey))
            } catch (_: Exception) {
                null
            } ?: continue
            val parsed = TmdbRatings.parse(body, now) ?: continue
            if (parsed.found) return parsed
            if (TmdbRatings.isNotFound(body)) confirmedMiss = true
        }
        return if (confirmedMiss) TmdbRating(0.0, 0, found = false, fetchedAtMs = now) else null
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val file = ratingFile()
            if (Files.isRegularFile(file)) {
                runCatching {
                    val parsed = json.decodeFromString<RatingFile>(Files.readString(file))
                    memory.putAll(parsed.entries)
                }
            }
            loaded = true
        }
    }

    private fun persist() {
        val snapshot = synchronized(lock) { memory.toMap() }
        runCatching {
            val file = ratingFile()
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, json.encodeToString(RatingFile(snapshot)))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun ratingFile(): Path = fileOverride ?: AppPaths.configDir.resolve("tmdb-ratings.json")

    private data class Pending(
        val kind: ContentKind,
        val tmdbId: String?,
        val cacheKey: String,
        val title: String?,
        val year: Int?
    )

    @Serializable
    private data class RatingFile(val entries: Map<String, TmdbRating> = emptyMap())
}
