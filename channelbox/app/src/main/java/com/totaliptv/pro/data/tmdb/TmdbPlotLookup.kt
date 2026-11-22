package com.totaliptv.pro.data.tmdb

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
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

/**
 * Plot / year / rating fallback when the provider left those fields blank.
 * Parsing is pure so unit tests never need the network or a key.
 */
object TmdbPlotLookup {
    const val API_BASE = "https://api.themoviedb.org/3"

    data class Details(
        val plot: String? = null,
        val year: Int? = null,
        val rating: String? = null,
        val tmdbId: Int? = null
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parseDetails(body: String): Details {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return Details()
        return detailsFrom(obj)
    }

    fun parseSearch(body: String, title: String, year: Int? = null): Details {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return Details()
        val results = root["results"]?.jsonArray ?: return Details()
        if (results.isEmpty()) return Details()
        val want = TitleMatch.normalize(title)
        val scored = results.mapNotNull { el ->
            val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val names = listOf(
                obj.str("name"),
                obj.str("original_name"),
                obj.str("title"),
                obj.str("original_title")
            )
            val keys = TitleMatch.keysForTmdb(names[0], names[1]) +
                TitleMatch.keysForTmdb(names[2], names[3])
            val exact = want.isNotBlank() && keys.contains(want)
            val d = detailsFrom(obj)
            val yearOk = year == null || d.year == null || year == d.year
            Triple(if (exact && yearOk) 2 else if (exact) 1 else if (yearOk && want.isNotBlank() && keys.any { it.contains(want) || want.contains(it) }) 0 else -1, d, obj)
        }.filter { it.first >= 0 }
        val best = scored.maxByOrNull { it.first }?.let { detailsFrom(it.third) }
        return best ?: detailsFrom(runCatching { results.first().jsonObject }.getOrNull() ?: return Details())
    }

    fun parseEpisodeOverview(body: String): String? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return null
        return obj.str("overview")?.trim()?.takeIf { it.isNotBlank() }
    }

    fun detailsFrom(obj: JsonObject): Details {
        val plot = obj.str("overview")?.trim()?.takeIf { it.isNotBlank() }
        val year = TitleMatch.yearFromDate(obj.str("first_air_date"))
            ?: TitleMatch.yearFromDate(obj.str("release_date"))
        val vote = obj["vote_average"]?.jsonPrimitive?.doubleOrNull
        val rating = vote?.takeIf { it > 0.0 }?.let { String.format(java.util.Locale.US, "%.1f", it) }
        val id = obj["id"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
        return Details(plot = plot, year = year, rating = rating, tmdbId = id)
    }

    fun apply(item: MediaItem, details: Details): MediaItem {
        var out = item
        if (out.plot.isNullOrBlank() && !details.plot.isNullOrBlank()) {
            out = out.copy(plot = details.plot)
        }
        if (out.year == null && details.year != null) {
            out = out.copy(year = details.year)
        }
        if (out.tmdbId == null && details.tmdbId != null) {
            out = out.copy(tmdbId = details.tmdbId)
        }
        if (out.displayRating() == null && !details.rating.isNullOrBlank()) {
            out = out.copy(rating = details.rating)
        }
        return out
    }

    /**
     * Fetch a show/movie plot when the provider left it blank.
     * Never logs the key or the raw URL (the query would include it).
     */
    fun fill(
        http: OkHttpClient,
        apiKey: String,
        item: MediaItem
    ): MediaItem {
        if (apiKey.isBlank()) return item
        if (item.kind != ContentKind.SERIES && item.kind != ContentKind.VOD) return item
        val isTv = item.kind == ContentKind.SERIES
        var details: Details? = null
        val knownId = item.tmdbId
        if (knownId != null && knownId > 0) {
            val path = if (isTv) "tv/$knownId" else "movie/$knownId"
            get(http, apiKey, path)?.let { details = parseDetails(it) }
        }
        if (details == null || details!!.plot.isNullOrBlank()) {
            val path = if (isTv) "search/tv" else "search/movie"
            val body = get(http, apiKey, path, mapOf("query" to item.name, "include_adult" to "false"))
            if (body != null) {
                val searched = parseSearch(body, item.name, item.year)
                details = merge(details, searched)
                val foundId = details?.tmdbId
                if ((details?.plot.isNullOrBlank()) && foundId != null && foundId != knownId) {
                    val detailPath = if (isTv) "tv/$foundId" else "movie/$foundId"
                    get(http, apiKey, detailPath)?.let { details = merge(details, parseDetails(it)) }
                }
            }
        }
        return details?.let { apply(item, it) } ?: item
    }

    fun fillEpisodeOverview(
        http: OkHttpClient,
        apiKey: String,
        tmdbId: Int?,
        title: String,
        year: Int?,
        season: Int,
        episodeNum: Int
    ): String? {
        if (apiKey.isBlank()) return null
        if (season <= 0 || episodeNum <= 0) return null
        var id = tmdbId?.takeIf { it > 0 }
        if (id == null) {
            val body = get(http, apiKey, "search/tv", mapOf("query" to title, "include_adult" to "false"))
                ?: return null
            id = parseSearch(body, title, year).tmdbId
        }
        val showId = id ?: return null
        val body = get(http, apiKey, "tv/$showId/season/$season/episode/$episodeNum") ?: return null
        return parseEpisodeOverview(body)
    }

    private fun merge(a: Details?, b: Details): Details {
        if (a == null) return b
        return Details(
            plot = a.plot?.takeIf { it.isNotBlank() } ?: b.plot,
            year = a.year ?: b.year,
            rating = a.rating ?: b.rating,
            tmdbId = a.tmdbId ?: b.tmdbId
        )
    }

    private fun get(
        http: OkHttpClient,
        apiKey: String,
        path: String,
        extra: Map<String, String> = emptyMap()
    ): String? {
        val url = "$API_BASE/$path".toHttpUrl().newBuilder()
            .addQueryParameter("language", "en-US")
            .apply {
                extra.forEach { (k, v) -> addQueryParameter(k, v) }
                if (!TmdbServiceLookup.isV4Token(apiKey)) addQueryParameter("api_key", apiKey)
            }
            .build()
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .apply { if (TmdbServiceLookup.isV4Token(apiKey)) header("Authorization", "Bearer $apiKey") }
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}
