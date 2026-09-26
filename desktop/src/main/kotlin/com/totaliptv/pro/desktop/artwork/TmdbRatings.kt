package com.totaliptv.pro.desktop.artwork

import com.totaliptv.pro.desktop.data.ContentKind
import com.totaliptv.pro.desktop.data.MediaItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class TmdbRating(
    val average: Double,
    val votes: Int,
    val found: Boolean,
    val fetchedAtMs: Long,
    /** Set when this row was resolved from a title search rather than a provider id. */
    val tmdbId: String? = null
)

/** How provider scores and TMDB votes become the number on a tile and the Top rated sort key. */
object TmdbRatings {
    const val MIN_VOTES_FOR_TOP = 20
    const val MAX_AGE_MS: Long = 7L * 24L * 60L * 60L * 1000L
    /** Search misses and unknown ids. Short so a later rename or new TMDB row can be picked up. */
    const val MISS_AGE_MS: Long = 24L * 60L * 60L * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    fun isFresh(fetchedAtMs: Long, nowMs: Long): Boolean = nowMs - fetchedAtMs < MAX_AGE_MS

    fun isFresh(entry: TmdbRating, nowMs: Long): Boolean {
        val limit = if (entry.found) MAX_AGE_MS else MISS_AGE_MS
        return nowMs - entry.fetchedAtMs < limit
    }

    fun batchLine(resolved: Int, missed: Int, elapsedMs: Long): String =
        "ratings resolved=$resolved missed=$missed ms=$elapsedMs"

    fun cacheKey(kind: ContentKind, tmdbId: String): String {
        val type = if (kind == ContentKind.SERIES) "tv" else "movie"
        return "$type:$tmdbId"
    }

    /** Provider score with padded 10.0s removed. There is no vote count on those rows. */
    fun providerScore(rating: String?, rating5Based: Double?): Double {
        val fromText = rating?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }
        val score = fromText ?: rating5Based?.takeIf { it > 0.0 }?.times(2.0) ?: 0.0
        return if (score >= 9.95) 0.0 else score
    }

    fun providerScore(item: MediaItem): Double = providerScore(item.rating, item.rating5Based)

    fun displayScore(provider: Double, tmdb: TmdbRating?, enabled: Boolean): Double {
        if (enabled && tmdb != null && tmdb.found && tmdb.average > 0.0) return tmdb.average
        return provider
    }

    /** Low vote counts stay visible on the tile but cannot lead a Top rated row. */
    fun rankScore(provider: Double, tmdb: TmdbRating?, enabled: Boolean): Double {
        if (enabled && tmdb != null && tmdb.found && tmdb.average > 0.0) {
            return if (tmdb.votes >= MIN_VOTES_FOR_TOP) tmdb.average else 0.0
        }
        return provider
    }

    fun parse(body: String, fetchedAtMs: Long): TmdbRating? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (root.containsKey("status_code") && root["vote_average"] == null) {
            return TmdbRating(0.0, 0, found = false, fetchedAtMs = fetchedAtMs)
        }
        val average = root["vote_average"]?.jsonPrimitive?.doubleOrNull
            ?: root["vote_average"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: return null
        val votes = root["vote_count"]?.jsonPrimitive?.intOrNull
            ?: root["vote_count"]?.jsonPrimitive?.doubleOrNull?.toInt()
            ?: 0
        return TmdbRating(average, votes, found = true, fetchedAtMs = fetchedAtMs)
    }

    /** TMDB status 34 is a real miss. Other status codes (bad key, rate limit) should be retried. */
    fun isNotFound(body: String): Boolean = statusCode(body) == 34

    /** Invalid key, rate limit, and other API errors. Do not cache these. */
    fun isHardFailure(body: String): Boolean {
        val code = statusCode(body) ?: return false
        return code != 34
    }

    private fun statusCode(body: String): Int? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return root["status_code"]?.jsonPrimitive?.intOrNull
    }

    fun endpoints(kind: ContentKind, tmdbId: String): List<String> {
        return if (kind == ContentKind.SERIES) listOf("tv/$tmdbId", "movie/$tmdbId")
        else listOf("movie/$tmdbId", "tv/$tmdbId")
    }
}
