package com.totaliptv.pro.desktop.artwork

import com.totaliptv.pro.desktop.data.ContentKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** One TMDB API call. v3 keys go on the query string; v4 read tokens use Bearer auth. */
data class TmdbRequest(val pathAndQuery: String, val key: String)

object TmdbAuth {
    fun normalize(raw: String): String = raw.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()

    /** v4 read access tokens are JWTs and start with eyJ. Anything else is treated as a v3 API key. */
    fun isV4ReadToken(key: String): Boolean = normalize(key).startsWith("eyJ")

    fun url(request: TmdbRequest): String {
        val path = request.pathAndQuery.trim().trimStart('/')
        val key = normalize(request.key)
        if (isV4ReadToken(key)) return "https://api.themoviedb.org/3/$path"
        val join = if (path.contains('?')) "&" else "?"
        return "https://api.themoviedb.org/3/$path$join" + "api_key=" + URLEncoder.encode(key, Charsets.UTF_8)
    }

    fun authorization(request: TmdbRequest): String? {
        val key = normalize(request.key)
        return if (isV4ReadToken(key)) "Bearer $key" else null
    }
}

enum class ArtworkRole {
    POSTER,
    BACKDROP,
    /** Channel logos stay small. Sharp-poster work does not apply. */
    LOGO
}

/**
 * TMDB image URL upgrades and optional API lookups.
 * With no API key, only [rewrite] runs. [lookup] does not call [fetch].
 */
object TmdbArtwork {
    private val json = Json { ignoreUnknownKeys = true }
    private val sized = Regex(
        """(?i)^(https?://image\.tmdb\.org/t/p/)(w\d+|h\d+|original)/(.+)$"""
    )

    fun posterTarget(): Int = 780
    fun backdropTarget(): Int = 1280

    fun targetLongEdge(role: ArtworkRole): Int = when (role) {
        ArtworkRole.POSTER -> posterTarget()
        ArtworkRole.BACKDROP -> backdropTarget()
        ArtworkRole.LOGO -> 256
    }

    /** Upgrade a TMDB size token when it is smaller than the role's target. Never shrinks. */
    fun rewrite(url: String?, role: ArtworkRole): String? {
        val raw = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (role == ArtworkRole.LOGO) return raw
        val match = sized.matchEntire(raw) ?: return raw
        val token = match.groupValues[2]
        val target = targetLongEdge(role)
        if (sizeRank(token) >= target) return raw
        val replacement = if (role == ArtworkRole.BACKDROP) "w1280" else "w780"
        return match.groupValues[1] + replacement + "/" + match.groupValues[3]
    }

    fun isTmdbImageUrl(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        return raw.contains("image.tmdb.org/t/p/", ignoreCase = true)
    }

    /** Decoded art is too small for the on-screen slot (would be upscaled). */
    fun isTooSmall(decodedWidth: Int, decodedHeight: Int, targetLongEdge: Int): Boolean {
        if (decodedWidth <= 0 || decodedHeight <= 0) return true
        val longEdge = maxOf(decodedWidth, decodedHeight)
        return longEdge < (targetLongEdge * 0.75f)
    }

    /**
     * TMDB API lookup. Returns null and does not call [fetch] when [apiKey] is blank.
     * [tmdbId] is tried first. Title + year search runs only when the id lookup misses.
     */
    fun lookup(
        apiKey: String?,
        tmdbId: String?,
        title: String?,
        year: Int?,
        kind: ContentKind?,
        role: ArtworkRole,
        fetch: (TmdbRequest) -> String?
    ): String? {
        val key = TmdbAuth.normalize(apiKey.orEmpty())
        if (key.isEmpty() || role == ArtworkRole.LOGO) return null
        val id = tmdbId?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
        if (id != null) {
            val fromId = imageFromId(key, id, kind, role, fetch)
            if (fromId != null) return fromId
        }
        val query = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return imageFromSearch(key, query, year, kind, role, fetch)
    }

    fun resolveApiKey(pref: String?, env: String?, fileText: String?): String {
        return pref?.trim().orEmpty()
            .ifBlank { env?.trim().orEmpty() }
            .ifBlank { fileText?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty() }
    }

    internal fun sizeRank(token: String): Int {
        val lower = token.lowercase()
        if (lower == "original") return Int.MAX_VALUE
        return lower.drop(1).toIntOrNull() ?: 0
    }

    private fun imageFromId(
        key: String,
        id: String,
        kind: ContentKind?,
        role: ArtworkRole,
        fetch: (TmdbRequest) -> String?
    ): String? {
        val paths = when (kind) {
            ContentKind.SERIES -> listOf("tv", "movie")
            else -> listOf("movie", "tv")
        }
        for (path in paths) {
            val body = fetch(TmdbRequest("$path/$id", key)) ?: continue
            imageUrl(body, role)?.let { return it }
        }
        return null
    }

    private fun imageFromSearch(
        key: String,
        title: String,
        year: Int?,
        kind: ContentKind?,
        role: ArtworkRole,
        fetch: (TmdbRequest) -> String?
    ): String? {
        val encoded = URLEncoder.encode(title, Charsets.UTF_8)
        val searches = when (kind) {
            ContentKind.SERIES -> listOf("search/tv" to "first_air_date_year", "search/movie" to "year")
            else -> listOf("search/movie" to "year", "search/tv" to "first_air_date_year")
        }
        for ((path, yearParam) in searches) {
            val yearQuery = year?.takeIf { it in 1900..2100 }?.let { "&$yearParam=$it" }.orEmpty()
            val body = fetch(TmdbRequest("$path?query=$encoded$yearQuery", key)) ?: continue
            imageUrl(body, role)?.let { return it }
        }
        return null
    }

    internal fun imageUrl(body: String, role: ArtworkRole): String? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val node = root["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: root
        val poster = node["poster_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        val backdrop = node["backdrop_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        val path = if (role == ArtworkRole.BACKDROP) backdrop ?: poster else poster ?: backdrop
        if (path.isNullOrBlank()) return null
        val size = if (role == ArtworkRole.BACKDROP) "w1280" else "w780"
        val suffix = if (path.startsWith("/")) path else "/$path"
        return "https://image.tmdb.org/t/p/$size$suffix"
    }
}
