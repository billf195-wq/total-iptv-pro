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
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Disk-cached TMDB vote_average values. Visible items jump the queue.
 * A Home backfill walks the rest slowly so Top rated can settle without blocking the page.
 */
object TmdbRatingStore {
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
    internal var pauseMs: Long = 1000
    internal var autoDrain: Boolean = true

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
                val id = item.tmdbId?.trim()?.takeIf { it.isNotEmpty() && it != "0" } ?: return@mapNotNull null
                if (item.kind != ContentKind.VOD && item.kind != ContentKind.SERIES) return@mapNotNull null
                val cacheKey = TmdbRatings.cacheKey(item.kind, id)
                val cached = memory[cacheKey] ?: alternate(item.kind, id)
                if (cached != null && TmdbRatings.isFresh(cached.fetchedAtMs, now)) return@mapNotNull null
                if (!queued.add(cacheKey)) {
                    if (front) moveToFront(cacheKey)
                    return@mapNotNull null
                }
                Pending(item.kind, id, cacheKey)
            }
            if (pending.isEmpty()) return
            if (front) pending.asReversed().forEach { queue.addFirst(it) } else pending.forEach { queue.addLast(it) }
        }
        if (autoDrain) kick()
    }

    internal fun resetForTests() {
        autoDrain = false
        pauseMs = 0
        synchronized(lock) {
            memory.clear()
            queued.clear()
            queue.clear()
            loaded = false
        }
        revisionState.value = 0
    }

    internal fun drainForTests() {
        drain()
    }

    internal fun queuedIdsForTests(): List<String> = synchronized(lock) { queue.map { it.tmdbId } }

    fun peek(item: MediaItem): TmdbRating? {
        val id = item.tmdbId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        ensureLoaded()
        return peekId(item.kind, id)
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
                drain()
            } finally {
                draining = false
                val more = synchronized(lock) { queue.isNotEmpty() }
                if (more) kick()
            }
        }
    }

    private fun drain() {
        val apiKey = ArtworkSettings.tmdbApiKey
        while (true) {
            val batch = synchronized(lock) {
                if (queue.isEmpty()) emptyList() else List(minOf(3, queue.size)) { queue.removeFirst() }
            }
            if (batch.isEmpty()) return
            var changed = false
            for (job in batch) {
                val rating = fetchRating(apiKey, job)
                synchronized(lock) {
                    queued.remove(job.cacheKey)
                    if (rating != null) {
                        memory[job.cacheKey] = rating
                        changed = true
                    }
                }
            }
            if (changed) {
                persist()
                revisionState.value = revisionState.value + 1
            }
            if (pauseMs > 0) Thread.sleep(pauseMs)
        }
    }

    private fun moveToFront(cacheKey: String) {
        val existing = queue.firstOrNull { it.cacheKey == cacheKey } ?: return
        queue.remove(existing)
        queue.addFirst(existing)
    }

    private fun fetchRating(apiKey: String, job: Pending): TmdbRating? {
        val now = nowMs()
        var confirmedMiss = false
        for (path in TmdbRatings.endpoints(job.kind, job.tmdbId)) {
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

    private data class Pending(val kind: ContentKind, val tmdbId: String, val cacheKey: String)

    @Serializable
    private data class RatingFile(val entries: Map<String, TmdbRating> = emptyMap())
}
