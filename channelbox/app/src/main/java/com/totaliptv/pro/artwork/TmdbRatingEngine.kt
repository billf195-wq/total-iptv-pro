package com.totaliptv.pro.artwork

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Disk-cached TMDB vote averages. Visible items are enqueued at the front.
 * Work runs off the caller thread in batches of 4–6, then a short pause.
 * A blank or unusable key never calls [fetch].
 */
class TmdbRatingEngine(
    internal val storeFile: File,
    var fetch: (TmdbRequest) -> String? = { TmdbClient.get(it) },
    var nowMs: () -> Long = { System.currentTimeMillis() },
    var log: (String) -> Unit = {},
    var ratingsEnabled: () -> Boolean = { true },
    var apiKey: () -> String = { "" },
    var batchSize: Int = DEFAULT_BATCH,
    var pauseMs: Long = 800L,
    var autoDrain: Boolean = true
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    /** Immutable map swapped as a whole. Readers never see a map that a writer is mutating. */
    private val tableState = MutableStateFlow<Map<String, TmdbRating>>(emptyMap())
    private val queued = HashSet<String>()
    private val queue = ArrayDeque<Pending>()
    private var loaded = false
    private val revisionState = MutableStateFlow(0)
    val revision: StateFlow<Int> = revisionState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val draining = AtomicBoolean(false)

    fun peek(item: MediaItem): TmdbRating? {
        ensureLoaded()
        return lookup(tableState.value, item)
    }

    /** One frozen score per id. Safe to sort. Does not read the live table again. */
    fun rankScores(items: List<MediaItem>): Map<String, Double> {
        ensureLoaded()
        val table = tableState.value
        val enabled = ratingsActive()
        val out = HashMap<String, Double>(items.size)
        for (item in items) {
            out[item.id] = TmdbRatings.rankScore(
                TmdbRatings.providerScore(item.rating),
                lookup(table, item),
                enabled
            )
        }
        return out
    }

    fun displayScore(item: MediaItem): Double {
        val provider = TmdbRatings.providerScore(item.rating)
        return TmdbRatings.displayScore(provider, peek(item), ratingsActive())
    }

    fun rankScore(item: MediaItem): Double {
        val provider = TmdbRatings.providerScore(item.rating)
        return TmdbRatings.rankScore(provider, peek(item), ratingsActive())
    }

    /** True when nothing is waiting. The in-flight batch has already left the queue. */
    fun isIdle(): Boolean = synchronized(lock) { queue.isEmpty() }

    /** Test hook: publish one rating without going through the network. */
    internal fun testingPut(key: String, rating: TmdbRating) {
        synchronized(lock) {
            val next = HashMap(tableState.value)
            next[key] = rating
            tableState.value = next
        }
        revisionState.value = revisionState.value + 1
    }

    fun ratingsActive(): Boolean = ratingsEnabled() && TmdbAuth.isUsable(apiKey())

    fun enqueue(items: List<MediaItem>, front: Boolean) {
        if (!ratingsActive()) return
        ensureLoaded()
        val now = nowMs()
        synchronized(lock) {
            val pending = items.mapNotNull { item ->
                val key = cacheKey(item) ?: return@mapNotNull null
                val cached = tableState.value[key]
                if (cached != null && TmdbRatings.isFresh(cached, now)) return@mapNotNull null
                if (!queued.add(key)) {
                    if (front) moveToFront(key)
                    return@mapNotNull null
                }
                val cleaned = TmdbTitle.clean(item.name)
                Pending(
                    kind = item.kind,
                    tmdbId = usableId(item.tmdbId),
                    title = cleaned.title,
                    year = cleaned.year,
                    normalized = cleaned.normalized,
                    cacheKey = key
                )
            }
            if (pending.isEmpty()) return
            if (front) pending.asReversed().forEach { queue.addFirst(it) } else pending.forEach { queue.addLast(it) }
        }
        if (autoDrain) kick()
    }

    fun queuedKeysForTests(): List<String> = synchronized(lock) { queue.map { it.cacheKey } }

    fun drainForTests() {
        while (processBatchSync()) {
            // Tests run the whole queue on the caller thread.
        }
    }

    private fun kick() {
        if (!draining.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (processBatchAsync()) {
                    // Next batch, after the pause inside processBatchAsync.
                }
            } catch (t: Throwable) {
                log("tmdb worker failed: ${failureText(t)}")
            } finally {
                draining.set(false)
                val more = synchronized(lock) { queue.isNotEmpty() && ratingsActive() }
                if (more) kick()
            }
        }
    }

    private fun processBatchSync(): Boolean {
        val key = currentKey() ?: return false
        val batch = takeBatch()
        if (batch.isEmpty()) return false
        val start = System.currentTimeMillis()
        val results = batch.map { job -> job to fetchSafely(key, job) }
        applyResults(results, System.currentTimeMillis() - start)
        return synchronized(lock) { queue.isNotEmpty() }
    }

    private suspend fun processBatchAsync(): Boolean {
        val key = currentKey() ?: return false
        val batch = takeBatch()
        if (batch.isEmpty()) return false
        val start = System.currentTimeMillis()
        val results = try {
            coroutineScope {
                batch.map { job ->
                    async(Dispatchers.IO) { job to fetchSafely(key, job) }
                }.awaitAll()
            }
        } catch (t: Throwable) {
            log("tmdb worker failed: ${failureText(t)}")
            batch.map { it to null }
        }
        applyResults(results, System.currentTimeMillis() - start)
        if (pauseMs > 0) delay(pauseMs)
        return synchronized(lock) { queue.isNotEmpty() }
    }

    private fun currentKey(): String? {
        if (!ratingsEnabled()) {
            clearQueue()
            return null
        }
        val key = TmdbAuth.normalize(apiKey())
        if (!TmdbAuth.isUsable(key)) {
            clearQueue()
            return null
        }
        return key
    }

    private fun clearQueue() {
        synchronized(lock) {
            queue.clear()
            queued.clear()
        }
    }

    private fun takeBatch(): List<Pending> {
        val count = batchSize.coerceIn(BATCH_MIN, BATCH_MAX)
        return synchronized(lock) {
            if (queue.isEmpty()) emptyList() else List(minOf(count, queue.size)) { queue.removeFirst() }
        }
    }

    private fun applyResults(results: List<Pair<Pending, TmdbRating?>>, elapsedMs: Long) {
        var resolved = 0
        var missed = 0
        var changed = false
        synchronized(lock) {
            val next = HashMap(tableState.value)
            for ((job, rating) in results) {
                queued.remove(job.cacheKey)
                if (rating == null) {
                    if (job.attempts < 1) {
                        queued.add(job.cacheKey)
                        queue.addLast(job.copy(attempts = job.attempts + 1))
                    }
                    continue
                }
                next[job.cacheKey] = rating
                val id = rating.tmdbId?.takeIf { it.isNotBlank() }
                if (id != null) next[idKey(job.kind, id)] = rating
                changed = true
                if (rating.found) resolved += 1 else missed += 1
            }
            if (changed) tableState.value = next
        }
        if (changed) {
            persist()
            revisionState.value = revisionState.value + 1
        }
        if (resolved + missed > 0) log(TmdbRatings.batchLine(resolved, missed, elapsedMs))
    }

    private fun fetchBody(request: TmdbRequest): String? {
        return try {
            fetch(request)
        } catch (t: Throwable) {
            log("tmdb lookup failed: ${failureText(t)}")
            null
        }
    }

    /** A failed lookup is logged and skipped. [Error] included, so a bad API call cannot crash the process. */
    private fun fetchSafely(apiKey: String, job: Pending): TmdbRating? {
        return try {
            fetchOne(apiKey, job)
        } catch (t: Throwable) {
            log("tmdb lookup failed: ${failureText(t)}")
            null
        }
    }

    private fun failureText(t: Throwable): String {
        val message = t.message.orEmpty().replace(API_KEY_IN_TEXT, "api_key=***")
        return if (message.isBlank()) t.javaClass.simpleName else "${t.javaClass.simpleName}: $message"
    }

    private fun fetchOne(apiKey: String, job: Pending): TmdbRating? {
        val now = nowMs()
        val id = job.tmdbId
        return if (id != null) fetchById(apiKey, job.kind, id, now) else fetchBySearch(apiKey, job, now)
    }

    private fun fetchById(apiKey: String, kind: ContentKind, id: String, now: Long): TmdbRating? {
        var confirmedMiss = false
        var sawBody = false
        for (path in TmdbRatings.endpoints(kind, id)) {
            val body = fetchBody(TmdbRequest(path, apiKey)) ?: continue
            sawBody = true
            val parsed = TmdbRatings.parse(body, now) ?: continue
            if (parsed.found) return parsed.copy(tmdbId = id)
            if (TmdbRatings.isNotFound(body)) {
                confirmedMiss = true
                continue
            }
            return null
        }
        if (!sawBody) return null
        return if (confirmedMiss) TmdbRating(0.0, 0, found = false, fetchedAtMs = now) else null
    }

    private fun fetchBySearch(apiKey: String, job: Pending, now: Long): TmdbRating? {
        if (job.normalized.isEmpty()) {
            return TmdbRating(0.0, 0, found = false, fetchedAtMs = now)
        }
        val cleaned = TmdbTitle.Cleaned(job.title, job.year, job.normalized)
        val order = if (job.kind == ContentKind.SERIES) {
            listOf(true, false)
        } else {
            listOf(false, true)
        }
        var sawOk = false
        var retry = false
        for (series in order) {
            val path = TmdbTitle.searchPath(series, job.title, job.year)
            val body = fetchBody(TmdbRequest(path, apiKey)) ?: continue
            when (val outcome = TmdbTitle.interpretSearch(body)) {
                TmdbTitle.SearchBody.Retry -> retry = true
                TmdbTitle.SearchBody.Empty -> sawOk = true
                is TmdbTitle.SearchBody.Hits -> {
                    sawOk = true
                    val hit = TmdbTitle.pick(outcome.hits, cleaned)
                    if (hit != null) {
                        return TmdbRating(
                            average = hit.average,
                            votes = hit.votes,
                            found = true,
                            fetchedAtMs = now,
                            tmdbId = hit.id
                        )
                    }
                }
            }
        }
        if (retry || !sawOk) return null
        return TmdbRating(0.0, 0, found = false, fetchedAtMs = now)
    }

    private fun moveToFront(cacheKey: String) {
        val existing = queue.firstOrNull { it.cacheKey == cacheKey } ?: return
        queue.remove(existing)
        queue.addFirst(existing)
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            if (storeFile.isFile) {
                runCatching {
                    val parsed = json.decodeFromString<RatingFile>(storeFile.readText())
                    tableState.value = HashMap(parsed.entries)
                }
            }
            loaded = true
        }
    }

    private fun lookup(table: Map<String, TmdbRating>, item: MediaItem): TmdbRating? {
        val key = cacheKey(item) ?: return null
        table[key]?.let { return it }
        val id = usableId(item.tmdbId) ?: return null
        val primary = idKey(item.kind, id)
        val alternate = idKey(otherKind(item.kind), id)
        return table[primary] ?: table[alternate]
    }

    private fun persist() {
        val snapshot = tableState.value.toMap()
        runCatching {
            storeFile.parentFile?.mkdirs()
            val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
            tmp.writeText(json.encodeToString(RatingFile(snapshot)))
            if (!tmp.renameTo(storeFile)) {
                storeFile.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private fun cacheKey(item: MediaItem): String? {
        if (item.kind != ContentKind.VOD && item.kind != ContentKind.SERIES) return null
        val id = usableId(item.tmdbId)
        if (id != null) return idKey(item.kind, id)
        val cleaned = TmdbTitle.clean(item.name)
        if (cleaned.normalized.isEmpty()) return null
        val type = if (item.kind == ContentKind.SERIES) "tv" else "movie"
        return "q:$type:${cleaned.normalized}:${cleaned.year ?: 0}"
    }

    private fun idKey(kind: ContentKind, id: String): String {
        val type = if (kind == ContentKind.SERIES) "tv" else "movie"
        return "$type:$id"
    }

    private fun otherKind(kind: ContentKind): ContentKind =
        if (kind == ContentKind.SERIES) ContentKind.VOD else ContentKind.SERIES

    private fun usableId(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it != "0" }

    private data class Pending(
        val kind: ContentKind,
        val tmdbId: String?,
        val title: String,
        val year: Int?,
        val normalized: String,
        val cacheKey: String,
        val attempts: Int = 0
    )

    @Serializable
    private data class RatingFile(val entries: Map<String, TmdbRating> = emptyMap())

    companion object {
        const val BATCH_MIN = 4
        const val BATCH_MAX = 6
        const val DEFAULT_BATCH = 5
        private val API_KEY_IN_TEXT = Regex("api_key=[^&\\s]+")
    }
}
