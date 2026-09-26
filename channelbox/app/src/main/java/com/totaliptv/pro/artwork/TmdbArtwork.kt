package com.totaliptv.pro.artwork

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** One TMDB API call. v3 keys go on the query string; v4 read tokens use Bearer auth. */
data class TmdbRequest(val pathAndQuery: String, val key: String)

object TmdbAuth {
    fun normalize(raw: String): String =
        raw.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()

    /** v4 read access tokens are JWTs and start with eyJ. */
    fun isV4ReadToken(key: String): Boolean = normalize(key).startsWith("eyJ")

    /** v3 keys are 32 hex characters. v4 read tokens start with eyJ. Anything else must not hit the API. */
    fun isUsable(key: String): Boolean {
        val normalized = normalize(key)
        if (normalized.isEmpty()) return false
        if (normalized.startsWith("eyJ") && normalized.length >= 20) return true
        return HEX_32.matches(normalized)
    }

    fun url(request: TmdbRequest): String {
        val path = request.pathAndQuery.trim().trimStart('/')
        val key = normalize(request.key)
        if (isV4ReadToken(key)) return "https://api.themoviedb.org/3/$path"
        val join = if (path.contains('?')) "&" else "?"
        return "https://api.themoviedb.org/3/$path$join" + "api_key=" +
            URLEncoder.encode(key, StandardCharsets.UTF_8)
    }

    fun authorization(request: TmdbRequest): String? {
        val key = normalize(request.key)
        return if (isV4ReadToken(key)) "Bearer $key" else null
    }

    private val HEX_32 = Regex("^[0-9a-fA-F]{32}$")
}

enum class ArtworkRole {
    /** Browse tiles. Target about w500. */
    POSTER,
    /** Detail poster. Target about w780. */
    DETAIL,
    /** Wide hero / backdrop. Target about w780. */
    BACKDROP,
    /** Channel logos stay small. Sharp-poster work does not apply. */
    LOGO
}

/**
 * TMDB image URL upgrades. With no API key this still rewrites existing image.tmdb.org URLs.
 * It never calls api.themoviedb.org.
 */
object TmdbArtwork {
    private val sized = Regex(
        """(?i)^(https?://image\.tmdb\.org/t/p/)(w\d+|h\d+|original)/(.+)$"""
    )

    fun posterTarget(): Int = 500
    fun detailTarget(): Int = 780

    fun targetRank(role: ArtworkRole): Int = when (role) {
        ArtworkRole.POSTER -> posterTarget()
        ArtworkRole.DETAIL, ArtworkRole.BACKDROP -> detailTarget()
        ArtworkRole.LOGO -> 0
    }

    /** Upgrade a TMDB size token when it is smaller than the role's target. Never shrinks. */
    fun rewrite(url: String?, role: ArtworkRole): String? {
        val raw = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (role == ArtworkRole.LOGO) return raw
        val match = sized.matchEntire(raw) ?: return raw
        val token = match.groupValues[2]
        if (sizeRank(token) >= targetRank(role)) return raw
        val replacement = if (role == ArtworkRole.POSTER) "w500" else "w780"
        return match.groupValues[1] + replacement + "/" + match.groupValues[3]
    }

    /** Settings field wins. A blank field falls back to the first non-blank line of the key file. */
    fun resolveApiKey(pref: String?, fileText: String?): String {
        val fromPref = pref?.trim().orEmpty()
        if (fromPref.isNotBlank()) return fromPref
        return fileText?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    internal fun sizeRank(token: String): Int {
        val lower = token.lowercase()
        if (lower == "original") return Int.MAX_VALUE
        return lower.drop(1).toIntOrNull() ?: 0
    }
}
