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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Fills the Netflix / Prime Video / HBO / Max / Apple TV+ groups from TMDB "discover" lists
 * (US watch providers + TV networks) and collects vote_average / vote_count from those pages
 * plus a light top-rated discover (titles not on the four services). Only list pages are
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
    )

    class LookupFailed(message: String) : Exception(message)

    /** Fetch all queries. [onProgress] gets (done, total). Throws [LookupFailed] on auth errors. */
    fun fetch(apiKey: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<TmdbServiceEntry> {
        val key = normalizeKey(apiKey)
        if (key.isEmpty()) throw LookupFailed("No TMDB API key")
        val out = ArrayList<TmdbServiceEntry>()
        val queries = ALL_QUERIES
        var done = 0
        for (q in queries) {
            var page = 1
            var totalPages = 1
            val pageLimit = q.pages ?: pagesPerQuery
            while (page <= minOf(totalPages, pageLimit)) {
                val body = get(key, q, page) ?: break
                val parsed = parseDiscover(body, q.type, q.service)
                out += parsed.entries
                totalPages = parsed.totalPages
                page++
                if (delayMs > 0) sleep(delayMs)
            }
            done++
            onProgress(done, queries.size)
        }
        return out
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
        /** Extra pages of globally top-rated titles (not limited to the four services). */
        const val RATING_PAGES = 40
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * Verified against TMDB /watch/providers (US) and /network on 2026-11-11:
         * providers Netflix 8, Amazon Prime Video 9, HBO Max 1899, Apple TV (TV+) 350;
         * networks Netflix 213, Prime Video 1024, HBO 49, HBO Max 3186, Apple TV 2552.
         */
        val QUERIES: List<Query> = listOf(
            Query("movie", "with_watch_providers", "8", StreamingService.NETFLIX),
            Query("movie", "with_watch_providers", "9", StreamingService.PRIME),
            Query("movie", "with_watch_providers", "1899", StreamingService.HBO),
            Query("movie", "with_watch_providers", "350", StreamingService.APPLE),
            Query("tv", "with_networks", "213", StreamingService.NETFLIX),
            Query("tv", "with_networks", "1024", StreamingService.PRIME),
            Query("tv", "with_networks", "49|3186", StreamingService.HBO),
            Query("tv", "with_networks", "2552", StreamingService.APPLE),
            Query("tv", "with_watch_providers", "8", StreamingService.NETFLIX),
            Query("tv", "with_watch_providers", "9", StreamingService.PRIME),
            Query("tv", "with_watch_providers", "1899", StreamingService.HBO),
            Query("tv", "with_watch_providers", "350", StreamingService.APPLE)
        )

        /**
         * Light top-rated lists so titles that are not on Netflix / Prime / HBO / Apple
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

        fun readCache(file: File): TmdbServiceCache? = runCatching {
            if (!file.isFile) return null
            cacheJson.decodeFromString(TmdbServiceCache.serializer(), file.readText())
        }.getOrNull()?.takeIf { it.version == TmdbServiceCache.CACHE_VERSION }

        fun writeCache(file: File, cache: TmdbServiceCache) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(cacheJson.encodeToString(TmdbServiceCache.serializer(), cache))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }

        fun isFresh(cache: TmdbServiceCache?, keyFingerprint: String, nowMs: Long): Boolean =
            cache != null && cache.keyFingerprint == keyFingerprint &&
                cache.entries.isNotEmpty() && nowMs - cache.fetchedAtMs in 0 until TTL_MS
    }
}
