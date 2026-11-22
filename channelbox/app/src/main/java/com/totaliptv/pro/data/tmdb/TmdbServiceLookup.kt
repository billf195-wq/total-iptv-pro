package com.totaliptv.pro.data.tmdb

import com.totaliptv.pro.data.Ratings
import com.totaliptv.pro.data.StreamingService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Fills the streaming service groups from TMDB "discover" lists
 * (US watch providers + TV networks) and collects vote_average / vote_count from those pages
 * plus a light top-rated discover (titles not on those services). Only list pages are
 * fetched; catalog titles are never sent to TMDB. Streaming availability on TMDB is provided
 * by JustWatch.
 *
 * Plain JVM (OkHttp + kotlinx.serialization) so it also runs in unit tests.
 */
class TmdbServiceLookup(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://api.themoviedb.org/3",
    private val pagesPerQuery: Int = DEFAULT_PAGES,
    private val delayMs: Long = 120L,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {
    /**
     * One discover query: TMDB type plus an optional provider/network filter and service.
     * [service] is null for ratings-only queries (top-rated movies / TV).
     */
    data class Query(
        val type: String,
        val param: String? = null,
        val value: String? = null,
        val service: StreamingService? = null,
        val sortBy: String = "popularity.desc",
        val watchRegion: String? = "US",
        val extraParams: Map<String, String> = emptyMap(),
        val pages: Int? = null
    ) {
        fun cursorKey(): String = buildString {
            append(type); append('|')
            append(param.orEmpty()); append('|')
            append(value.orEmpty()); append('|')
            append(sortBy); append('|')
            append(watchRegion.orEmpty()); append('|')
            append(service?.key.orEmpty())
            extraParams.entries.sortedBy { it.key }.forEach { (k, v) ->
                append('|'); append(k); append('='); append(v)
            }
        }
    }

    class LookupFailed(message: String) : Exception(message)

    data class FetchResult(
        val entries: List<TmdbServiceEntry>,
        val complete: Boolean,
        val progress: List<TmdbQueryCursor>,
        val paused: Boolean = false
    )

    /**
     * Which work a cache needs. [queries] is already ordered (missing / new services first).
     * [progress] covers every discover query: skipped services are marked done so a restart
     * never walks them again. [fetchedAtMs] is the value to persist (kept on an incremental
     * fill so the 7-day clock is not reset).
     */
    data class FetchPlan(
        val kind: Kind,
        val queries: List<Query>,
        val progress: List<TmdbQueryCursor>,
        val fetchedAtMs: Long,
        val fullRecheck: Boolean,
        val completedServices: Set<String>,
        val reason: String
    ) {
        enum class Kind { None, FillMissing, FullRecheck }
        val isNoop: Boolean get() = kind == Kind.None
    }

    /**
     * Fetch the planned queries with limited concurrency. Entries are merged as pages
     * arrive. Pass [seed] / [progress] from [FetchPlan] so a restart continues the same
     * page instead of starting over. [onQueryStart] is called with the user-facing status
     * line (e.g. "Updating service groups: Peacock…"). [onServiceComplete] fires when
     * every query for a service (or the ratings pass) has finished, so the caller can
     * rebuild the in-memory index without copying the full list after every page.
     * [shouldPause] stops after the current page and returns an incomplete result.
     */
    fun fetch(
        apiKey: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onQueryStart: (String) -> Unit = {},
        onServiceComplete: (String?) -> Unit = {},
        onPersist: (List<TmdbServiceEntry>, List<TmdbQueryCursor>, Boolean) -> Unit = { _, _, _ -> },
        isActive: () -> Boolean = { true },
        shouldPause: () -> Boolean = { false },
        seed: List<TmdbServiceEntry> = emptyList(),
        progress: List<TmdbQueryCursor> = emptyList(),
        queries: List<Query> = ALL_QUERIES,
        concurrency: Int = DEFAULT_CONCURRENCY,
        persistEveryPages: Int = PERSIST_EVERY_PAGES
    ): FetchResult {
        val persistEvery = persistEveryPages.coerceAtLeast(1)
        val key = normalizeKey(apiKey)
        if (key.isEmpty()) throw LookupFailed("No TMDB API key")
        val merged = LinkedHashMap<String, TmdbServiceEntry>(seed.size + 64)
        for (e in seed) {
            merged["${e.type}:${e.id}"] = e
        }
        val lock = Any()
        val doneCount = AtomicInteger(progress.count { it.done })
        val failed = AtomicReference<LookupFailed>(null)
        val paused = java.util.concurrent.atomic.AtomicBoolean(false)
        val cursors = LinkedHashMap<String, TmdbQueryCursor>()
        for (c in progress) cursors[c.key] = c
        fun snapshotProgress(): List<TmdbQueryCursor> = ALL_QUERIES.map { q ->
            cursors[q.cursorKey()] ?: TmdbQueryCursor(q.cursorKey())
        }
        fun persist(done: Boolean) {
            val (entries, prog) = synchronized(lock) {
                // One copy, only when writing the checkpoint.
                ArrayList(merged.values) to snapshotProgress()
            }
            onPersist(entries, prog, done)
        }
        fun maybeServiceComplete(q: Query) {
            val keyOrNull = q.service?.key
            val group = if (keyOrNull != null) {
                ALL_QUERIES.filter { it.service?.key == keyOrNull }
            } else {
                ALL_QUERIES.filter { it.service == null }
            }
            val allDone = synchronized(lock) {
                group.all { cursors[it.cursorKey()]?.done == true }
            }
            if (allDone) onServiceComplete(keyOrNull)
        }
        val workers = concurrency.coerceIn(1, MAX_CONCURRENCY)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val tasks = queries.map { q ->
                Callable {
                    val qKey = q.cursorKey()
                    val start = synchronized(lock) { cursors[qKey] }
                    if (start?.done == true) {
                        onProgress(doneCount.get(), queries.size)
                        return@Callable
                    }
                    if (!isActive() || failed.get() != null) return@Callable
                    if (shouldPause()) {
                        paused.set(true)
                        return@Callable
                    }
                    onQueryStart(updatingStatus(q))
                    var page = (start?.nextPage ?: 1).coerceAtLeast(1)
                    val pageLimit = q.pages ?: pagesPerQuery
                    // Do not seed totalPages at 1: a resume at page 4 would otherwise skip the rest.
                    var totalPages = pageLimit
                    while (page <= minOf(totalPages, pageLimit) && isActive() && failed.get() == null) {
                        if (shouldPause()) {
                            paused.set(true)
                            synchronized(lock) {
                                cursors[qKey] = TmdbQueryCursor(qKey, nextPage = page, done = false)
                            }
                            persist(false)
                            return@Callable
                        }
                        val body = try {
                            get(key, q, page)
                        } catch (e: LookupFailed) {
                            failed.compareAndSet(null, e)
                            return@Callable
                        } ?: break
                        val parsed = parseDiscover(body, q.type, q.service)
                        synchronized(lock) {
                            for (e in parsed.entries) {
                                val k = "${e.type}:${e.id}"
                                val prev = merged[k]
                                merged[k] = if (prev == null) e else ServiceIndex.mergeEntries(prev, e)
                            }
                            cursors[qKey] = TmdbQueryCursor(qKey, nextPage = page + 1, done = false)
                        }
                        if (page == 1 || page % persistEvery == 0) persist(false)
                        totalPages = parsed.totalPages
                        page++
                        if (delayMs > 0 && page <= minOf(totalPages, pageLimit)) {
                            try {
                                sleep(delayMs)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                synchronized(lock) {
                                    cursors[qKey] = TmdbQueryCursor(qKey, nextPage = page, done = false)
                                }
                                persist(false)
                                return@Callable
                            }
                        }
                    }
                    val finished = failed.get() == null && isActive() && !paused.get()
                    if (finished) {
                        synchronized(lock) { cursors[qKey] = TmdbQueryCursor(qKey, nextPage = page, done = true) }
                        onProgress(doneCount.incrementAndGet(), queries.size)
                        persist(false)
                        maybeServiceComplete(q)
                    }
                }
            }
            pool.invokeAll(tasks)
            failed.get()?.let { throw it }
        } finally {
            pool.shutdownNow()
        }
        val (entries, prog) = synchronized(lock) { ArrayList(merged.values) to snapshotProgress() }
        val reallyComplete = !paused.get() && isActive() && failed.get() == null && prog.all { it.done }
        persist(reallyComplete)
        return FetchResult(entries, reallyComplete, prog, paused = paused.get())
    }

    private fun get(key: String, q: Query, page: Int): String? {
        val url = "$baseUrl/discover/${q.type}".toHttpUrl().newBuilder()
            .addQueryParameter("language", "en-US")
            .addQueryParameter("sort_by", q.sortBy)
            .addQueryParameter("include_adult", "false")
            .addQueryParameter("page", page.toString())
            .apply {
                q.watchRegion?.let { addQueryParameter("watch_region", it) }
                if (!q.param.isNullOrBlank() && q.value != null) addQueryParameter(q.param, q.value)
                q.extraParams.forEach { (k, v) -> addQueryParameter(k, v) }
                if (!isV4Token(key)) addQueryParameter("api_key", key)
            }
            .build()
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .apply { if (isV4Token(key)) header("Authorization", "Bearer $key") }
            .build()
        for (attempt in 0 until 4) {
            val resp = try {
                http.newCall(req).execute()
            } catch (t: Throwable) {
                // Network hiccup: retry a little, then give up on this query.
                if (attempt == 3) return null
                sleep(1_000L * (attempt + 1))
                continue
            }
            resp.use { r ->
                when {
                    r.isSuccessful -> return r.body?.string()
                    r.code == 401 || r.code == 403 -> throw LookupFailed("TMDB rejected the API key (HTTP ${r.code})")
                    r.code == 429 -> {
                        val wait = r.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 10) ?: 2
                        sleep(wait * 1000L)
                    }
                    else -> return null
                }
            }
        }
        return null
    }

    data class Page(val entries: List<TmdbServiceEntry>, val totalPages: Int)

    companion object {
        const val DEFAULT_PAGES = 30
        /** Extra pages of globally top-rated titles (not limited to the service groups). */
        const val RATING_PAGES = 40
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000
        /** One query at a time on TV boxes so a large catalog does not pin several page lists plus a full snapshot. */
        const val DEFAULT_CONCURRENCY = 1
        const val MAX_CONCURRENCY = 2
        /** Mid-query JSON checkpoint cadence. Query completion still always persists. */
        const val PERSIST_EVERY_PAGES = 5

        /**
         * Verified against TMDB /watch/providers (US) and /network on 2026-11-12:
         * providers Netflix 8, Amazon Prime Video 9, HBO Max 1899, Apple TV (TV+) 350,
         * Hulu 15, Disney Plus 337, Paramount Plus 531, Paramount+ with Showtime 1770,
         * Paramount+ Amazon Channel 582;
         * networks Netflix 213, Prime Video 1024, HBO 49, HBO Max 3186, Apple TV 2552,
         * Hulu 453, Disney+ 2739, Paramount+ 4330.
         * Paramount Network (2076) and Disney Channel / Junior are not included.
         */
        val QUERIES: List<Query> = listOf(
            Query("movie", "with_watch_providers", "8", StreamingService.NETFLIX),
            Query("tv", "with_networks", "213", StreamingService.NETFLIX),
            Query("tv", "with_watch_providers", "8", StreamingService.NETFLIX),
            Query("movie", "with_watch_providers", "9", StreamingService.PRIME),
            Query("tv", "with_networks", "1024", StreamingService.PRIME),
            Query("tv", "with_watch_providers", "9", StreamingService.PRIME),
            Query("movie", "with_watch_providers", "1899", StreamingService.HBO),
            Query("tv", "with_networks", "49|3186", StreamingService.HBO),
            Query("tv", "with_watch_providers", "1899", StreamingService.HBO),
            Query("movie", "with_watch_providers", "350", StreamingService.APPLE),
            Query("tv", "with_networks", "2552", StreamingService.APPLE),
            Query("tv", "with_watch_providers", "350", StreamingService.APPLE),
            Query("movie", "with_watch_providers", "15", StreamingService.HULU),
            Query("tv", "with_networks", "453", StreamingService.HULU),
            Query("tv", "with_watch_providers", "15", StreamingService.HULU),
            Query("movie", "with_watch_providers", "337", StreamingService.DISNEY),
            Query("tv", "with_networks", "2739", StreamingService.DISNEY),
            Query("tv", "with_watch_providers", "337", StreamingService.DISNEY),
            Query("movie", "with_watch_providers", "531|1770|582", StreamingService.PARAMOUNT),
            Query("tv", "with_networks", "4330", StreamingService.PARAMOUNT),
            Query("tv", "with_watch_providers", "531|1770|582", StreamingService.PARAMOUNT),
            // Verified 2026-11-21 against TMDB /watch/providers?watch_region=US (movie + tv).
            // Peacock lists as "Peacock Premium" 386 and "Peacock Premium Plus" 387.
            // Discovery+ lists as "Discovery +" 520 (older 524 is no longer on the US list).
            // BET+ is not on the current TMDB provider list, so it has no discover query
            // (the chip still appears from category/title matches when any titles exist).
            Query("movie", "with_watch_providers", "386|387", StreamingService.PEACOCK),
            Query("tv", "with_watch_providers", "386|387", StreamingService.PEACOCK),
            Query("movie", "with_watch_providers", "43", StreamingService.STARZ),
            Query("tv", "with_watch_providers", "43", StreamingService.STARZ),
            Query("movie", "with_watch_providers", "34", StreamingService.MGM),
            Query("tv", "with_watch_providers", "34", StreamingService.MGM),
            Query("movie", "with_watch_providers", "526", StreamingService.AMC),
            Query("tv", "with_watch_providers", "526", StreamingService.AMC),
            Query("movie", "with_watch_providers", "520", StreamingService.DISCOVERY),
            Query("tv", "with_watch_providers", "520", StreamingService.DISCOVERY),
            Query("movie", "with_watch_providers", "283", StreamingService.CRUNCHYROLL),
            Query("tv", "with_watch_providers", "283", StreamingService.CRUNCHYROLL)
        )

        /**
         * Light top-rated lists so titles that are not on the service groups
         * still have a TMDB score. Sorted by vote_average with the same vote-count floor
         * used when ranking; no watch-region filter so the list is global.
         */
        val RATING_QUERIES: List<Query> = listOf(
            Query(
                type = "movie",
                sortBy = "vote_average.desc",
                watchRegion = null,
                extraParams = mapOf("vote_count.gte" to Ratings.TMDB_MIN_VOTE_COUNT.toString()),
                pages = RATING_PAGES
            ),
            Query(
                type = "tv",
                sortBy = "vote_average.desc",
                watchRegion = null,
                extraParams = mapOf("vote_count.gte" to Ratings.TMDB_MIN_VOTE_COUNT.toString()),
                pages = RATING_PAGES
            )
        )

        val ALL_QUERIES: List<Query> = QUERIES + RATING_QUERIES

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val cacheJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun normalizeKey(raw: String?): String =
            raw?.trim()?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim().orEmpty()

        /** v4 read-access tokens are JWTs; v3 keys are 32 hex chars. */
        fun isV4Token(key: String): Boolean = key.length > 40 && key.count { it == '.' } >= 2

        /** Non-reversible short tag so a changed key triggers a refetch. */
        fun fingerprint(key: String): String {
            val k = normalizeKey(key)
            if (k.isEmpty()) return ""
            val d = MessageDigest.getInstance("SHA-256").digest(k.toByteArray())
            return d.take(4).joinToString("") { "%02x".format(it) }
        }

        fun parseDiscover(body: String, type: String, service: StreamingService?): Page {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return Page(emptyList(), 0)
            val total = root["total_pages"]?.jsonPrimitive?.intOrNull ?: 1
            val results = runCatching { root["results"]?.jsonArray }.getOrNull() ?: return Page(emptyList(), total)
            val tv = type == "tv"
            val entries = results.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val title = o[if (tv) "name" else "title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val original = o[if (tv) "original_name" else "original_title"]?.jsonPrimitive?.contentOrNull
                val date = o[if (tv) "first_air_date" else "release_date"]?.jsonPrimitive?.contentOrNull
                TmdbServiceEntry(
                    type = type,
                    id = id,
                    title = title,
                    originalTitle = original?.takeIf { it != title },
                    year = TitleMatch.yearFromDate(date),
                    services = listOfNotNull(service?.key),
                    voteAverage = jsonDouble(o, "vote_average"),
                    voteCount = o["vote_count"]?.jsonPrimitive?.intOrNull
                )
            }
            return Page(entries, total)
        }

        private fun jsonDouble(o: JsonObject, key: String): Double? {
            val p = o[key]?.jsonPrimitive ?: return null
            return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun readCache(file: File, acceptOlderVersion: Boolean = false): TmdbServiceCache? = runCatching {
            if (!file.isFile) return null
            file.inputStream().buffered().use { ins ->
                cacheJson.decodeFromStream(TmdbServiceCache.serializer(), ins)
            }
        }.getOrNull()?.takeIf { cache ->
            cache.entries.isNotEmpty() && (acceptOlderVersion || cache.version == TmdbServiceCache.CACHE_VERSION)
        }

        /** Older cache versions are good enough to show groups instantly while a refresh runs. */
        fun readUsableCache(file: File): TmdbServiceCache? = readCache(file, acceptOlderVersion = true)

        @OptIn(ExperimentalSerializationApi::class)
        fun writeCache(file: File, cache: TmdbServiceCache): Boolean =
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.outputStream().buffered().use { out ->
                    cacheJson.encodeToStream(TmdbServiceCache.serializer(), cache, out)
                    out.flush()
                }
                if (!tmp.renameTo(file)) {
                    file.delete()
                    if (!tmp.renameTo(file)) return@runCatching false
                }
                file.isFile && file.length() > 0L
            }.getOrDefault(false)

        /** Service keys the current discover query list will fetch (BET+ is keyword-only and omitted). */
        fun serviceKeys(queries: List<Query> = QUERIES): Set<String> =
            queries.mapNotNull { it.service?.key }.toSet()

        fun serviceLabel(key: String?): String? =
            StreamingService.entries.firstOrNull { it.key == key }?.label

        fun updatingStatus(query: Query): String =
            query.service?.let { "Updating service groups: ${it.label}…" }
                ?: "Updating service groups: ratings…"

        /**
         * Services we will not refetch on an incremental fill.
         *
         * Prefer [TmdbServiceCache.completedServices] when present (written from 1.4.112).
         * For 1.4.111 files that list every service in [TmdbServiceCache.queriedServices]
         * before those queries actually ran, infer from entry hits only.
         */
        fun inferredCompleted(
            cache: TmdbServiceCache,
            expected: Set<String> = serviceKeys()
        ): Set<String> {
            if (cache.completedServices.isNotEmpty()) {
                return cache.completedServices.filter { it in expected || expected.isEmpty() }.toSet()
                    .ifEmpty { cache.completedServices.toSet() }
            }
            if (cache.complete && cache.queriedServices.isNotEmpty()) {
                return cache.queriedServices.toSet()
            }
            return cache.entries.flatMap { it.services }.toSet()
        }

        /**
         * Services present on a finished cache, or inferred from hits / completedServices
         * on a partial file. Does not trust an incomplete file's queriedServices list.
         */
        fun fetchedServiceKeys(cache: TmdbServiceCache): Set<String> = inferredCompleted(cache)

        fun missingServices(
            cache: TmdbServiceCache?,
            expected: Set<String> = serviceKeys()
        ): Set<String> {
            if (expected.isEmpty()) return emptySet()
            if (cache == null) return expected
            return expected - inferredCompleted(cache, expected)
        }

        private fun cursorMap(cache: TmdbServiceCache): Map<String, TmdbQueryCursor> =
            cache.progress.associateBy { it.key }

        /** Existing cursor if this query should resume; otherwise start at page 1. */
        private fun resumeCursor(cache: TmdbServiceCache?, query: Query): TmdbQueryCursor {
            val existing = cache?.let { cursorMap(it)[query.cursorKey()] }
            return if (existing != null && !existing.done && existing.nextPage > 1) existing
            else TmdbQueryCursor(query.cursorKey(), nextPage = 1, done = false)
        }

        /**
         * Build the work list for this launch. Never drops existing entries.
         * Incremental fills only the services missing from the saved file, new ones first.
         * A 7-day or forced re-check walks every service but is still resumable.
         */
        fun planFetch(
            cache: TmdbServiceCache?,
            keyFingerprint: String,
            nowMs: Long,
            force: Boolean = false,
            expectedServices: Set<String> = serviceKeys(),
            allQueries: List<Query> = ALL_QUERIES
        ): FetchPlan {
            val completed = cache?.let { inferredCompleted(it, expectedServices) } ?: emptySet()
            val missing = if (expectedServices.isEmpty()) emptySet() else expectedServices - completed
            val aged = cache == null || cache.entries.isEmpty() ||
                (nowMs - cache.fetchedAtMs) !in 0 until TTL_MS
            val midRecheck = cache?.fullRecheck == true && !cache.complete
            val fingerprintChanged = cache != null && cache.keyFingerprint.isNotEmpty() &&
                cache.keyFingerprint != keyFingerprint

            val kind = when {
                force || midRecheck || fingerprintChanged || cache == null || cache.entries.isEmpty() || aged ->
                    FetchPlan.Kind.FullRecheck
                missing.isNotEmpty() -> FetchPlan.Kind.FillMissing
                else -> FetchPlan.Kind.None
            }
            if (kind == FetchPlan.Kind.None) {
                return FetchPlan(
                    kind = kind,
                    queries = emptyList(),
                    progress = emptyList(),
                    fetchedAtMs = cache?.fetchedAtMs ?: nowMs,
                    fullRecheck = false,
                    completedServices = completed,
                    reason = "fresh"
                )
            }

            val prefer = when {
                kind == FetchPlan.Kind.FillMissing -> missing
                midRecheck -> {
                    // Still mid a 7-day / forced pass: finish already-started work, then the rest.
                    val pending = expectedServices.filter { key ->
                        allQueries.filter { it.service?.key == key }.any { q ->
                            cursorMap(cache!!)[q.cursorKey()]?.done != true
                        }
                    }.toSet()
                    (missing + pending)
                }
                else -> missing
            }
            val ordered = orderedQueries(prefer, allQueries)

            val progress = if (kind == FetchPlan.Kind.FillMissing) {
                allQueries.map { q ->
                    val key = q.service?.key
                    if (key != null && key in completed) TmdbQueryCursor(q.cursorKey(), done = true)
                    else if (q.service == null) TmdbQueryCursor(q.cursorKey(), done = true) // ratings wait for the 7-day pass
                    else resumeCursor(cache, q)
                }
            } else if (midRecheck && cache != null) {
                allQueries.map { q ->
                    cursorMap(cache)[q.cursorKey()] ?: TmdbQueryCursor(q.cursorKey())
                }
            } else {
                allQueries.map { q -> TmdbQueryCursor(q.cursorKey(), nextPage = 1, done = false) }
            }
            val toRun = ordered.filter { q -> progress.none { it.key == q.cursorKey() && it.done } }
            val reason = when {
                force -> "forced"
                midRecheck -> "incomplete"
                fingerprintChanged -> "fingerprint"
                cache == null || cache.entries.isEmpty() -> "missing"
                aged -> "age"
                else -> "providers"
            }
            val keptCompleted = if (kind == FetchPlan.Kind.FillMissing) completed else {
                if (midRecheck) completed else emptySet()
            }
            val fetchedAt = if (kind == FetchPlan.Kind.FillMissing) {
                cache?.fetchedAtMs?.takeIf { it > 0L } ?: nowMs
            } else if (midRecheck) {
                cache?.fetchedAtMs?.takeIf { it > 0L } ?: nowMs
            } else {
                nowMs
            }
            return FetchPlan(
                kind = kind,
                queries = toRun,
                progress = progress,
                fetchedAtMs = fetchedAt,
                fullRecheck = kind == FetchPlan.Kind.FullRecheck,
                completedServices = keptCompleted,
                reason = reason
            )
        }

        /** Missing / preferred services first (their movie + tv queries), then the others, then ratings. */
        fun orderedQueries(prefer: Set<String>, allQueries: List<Query> = ALL_QUERIES): List<Query> {
            val missingQs = allQueries.filter { it.service?.key in prefer }
            val otherQs = allQueries.filter { it.service != null && it.service.key !in prefer }
            val ratingQs = allQueries.filter { it.service == null }
            return missingQs + otherQs + ratingQs
        }

        /** Why a cache cannot be reused as-is. Never includes the key or fingerprint value. */
        fun staleReason(
            cache: TmdbServiceCache?,
            keyFingerprint: String,
            nowMs: Long,
            expectedServices: Set<String> = serviceKeys(),
            currentAppVersion: String = ""
        ): String? {
            if (cache == null) return "missing"
            if (cache.entries.isEmpty()) return "empty"
            if (cache.version != TmdbServiceCache.CACHE_VERSION && cache.version != 0) {
                // Older files are still a usable seed; if they lack current providers say so.
                if (missingServices(cache, expectedServices).isNotEmpty()) return "providers"
                if (cache.version != TmdbServiceCache.CACHE_VERSION) return "version ${cache.version}"
            }
            if (cache.keyFingerprint != keyFingerprint) return "fingerprint"
            val missing = missingServices(cache, expectedServices)
            if (missing.isNotEmpty()) return "providers"
            if (cache.fullRecheck && !cache.complete) return "incomplete"
            if (!cache.complete) {
                // Incremental fill of a 1.4.111 file that already has every current service:
                // treat it as finished so we do not walk the original lists again.
                return null
            }
            // An app update alone does not refetch when every current service is already on file.
            if (currentAppVersion.isNotBlank() && cache.appVersion.isNotBlank() &&
                cache.appVersion != currentAppVersion && missing.isNotEmpty()
            ) {
                return "app-update"
            }
            val age = nowMs - cache.fetchedAtMs
            if (age !in 0 until TTL_MS) return "age"
            return null
        }

        fun isFresh(
            cache: TmdbServiceCache?,
            keyFingerprint: String,
            nowMs: Long,
            expectedServices: Set<String> = serviceKeys(),
            currentAppVersion: String = ""
        ): Boolean = staleReason(cache, keyFingerprint, nowMs, expectedServices, currentAppVersion) == null

    }
}
