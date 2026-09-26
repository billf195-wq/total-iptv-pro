package com.totaliptv.pro.artwork

import com.totaliptv.pro.data.model.ContentKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

@Serializable
data class TmdbRating(
    val average: Double,
    val votes: Int,
    val found: Boolean,
    val fetchedAtMs: Long,
    val tmdbId: String? = null
)

/** Provider scores and TMDB votes: the badge number, and the Top rated sort key. */
object TmdbRatings {
    const val MIN_VOTES_FOR_TOP = 20
    const val MAX_AGE_MS: Long = 7L * 24L * 60L * 60L * 1000L
    const val MISS_AGE_MS: Long = 24L * 60L * 60L * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    fun isFresh(rating: TmdbRating, nowMs: Long): Boolean {
        val max = if (rating.found) MAX_AGE_MS else MISS_AGE_MS
        return nowMs - rating.fetchedAtMs < max
    }

    /** Provider score with padded 10.0s removed. There is no vote count on those rows. */
    fun providerScore(rating: String?): Double {
        val score = rating?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 0.0
        return if (score >= 9.95) 0.0 else score
    }

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

    fun formatScore(score: Double): String? {
        if (score <= 0.0) return null
        return String.format(Locale.US, "%.1f", score)
    }

    fun batchLine(resolved: Int, missed: Int, ms: Long): String =
        "tmdb ratings resolved=$resolved missed=$missed ms=$ms"

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
    fun isNotFound(body: String): Boolean {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return false
        return root["status_code"]?.jsonPrimitive?.intOrNull == 34
    }

    fun endpoints(kind: ContentKind, tmdbId: String): List<String> {
        return if (kind == ContentKind.SERIES) listOf("tv/$tmdbId", "movie/$tmdbId")
        else listOf("movie/$tmdbId", "tv/$tmdbId")
    }
}
