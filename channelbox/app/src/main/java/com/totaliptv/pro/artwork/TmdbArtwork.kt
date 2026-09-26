package com.totaliptv.pro.artwork

import java.net.URLEncoder

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
            URLEncoder.encode(key, "UTF-8")
    }

    fun authorization(request: TmdbRequest): String? {
        val key = normalize(request.key)
        return if (isV4ReadToken(key)) "Bearer $key" else null
    }

    private val HEX_32 = Regex("^[0-9a-fA-F]{32}$")
}

enum class ArtworkRole {
    /** Browse tiles. Target about w342, decoded at the tile. */
    POSTER,
    /** Detail poster. Target about w780. */
    DETAIL,
    /** Wide hero / backdrop. Target about w780. */
    BACKDROP,
    /** Channel logos stay small. Sharp-poster work does not apply. */
    LOGO
}

/**
 * Decode caps. Grid tiles stop at w342. Detail and backdrop stop at w780.
 * The bitmap is the on-screen size (dp × density), never the original file.
 */
object ArtworkDecode {
    const val POSTER_MAX_W = 342
    const val POSTER_MAX_H = 513
    const val DETAIL_MAX_W = 780
    const val DETAIL_MAX_H = 1170
    const val BACKDROP_MAX_W = 780
    const val BACKDROP_MAX_H = 439
    const val MEMORY_CACHE_CAP_BYTES = 96L * 1024 * 1024
    const val DISK_CACHE_CAP_BYTES = 200L * 1024 * 1024
    const val DISK_CACHE_DIR = "image_cache"

    /** Fit [widthDp] × [heightDp] into the cap, keeping the tile aspect. */
    fun pixels(widthDp: Float, heightDp: Float, density: Float, maxW: Int, maxH: Int): Pair<Int, Int> {
        val scale = density.coerceAtLeast(0.5f)
        var w = (widthDp.coerceAtLeast(1f) * scale).toInt().coerceAtLeast(1)
        var h = (heightDp.coerceAtLeast(1f) * scale).toInt().coerceAtLeast(1)
        if (w > maxW) {
            h = ((h * maxW.toFloat()) / w).toInt().coerceAtLeast(1)
            w = maxW
        }
        if (h > maxH) {
            w = ((w * maxH.toFloat()) / h).toInt().coerceAtLeast(1)
            h = maxH
        }
        return w to h
    }

    /** Clamp each side. Used for wide heroes so a short banner is not squeezed. */
    fun clamp(widthDp: Float, heightDp: Float, density: Float, maxW: Int, maxH: Int): Pair<Int, Int> {
        val scale = density.coerceAtLeast(0.5f)
        val w = (widthDp.coerceAtLeast(1f) * scale).toInt().coerceIn(1, maxW)
        val h = (heightDp.coerceAtLeast(1f) * scale).toInt().coerceIn(1, maxH)
        return w to h
    }

    /** 15% of the process heap, and never more than [MEMORY_CACHE_CAP_BYTES]. */
    fun memoryCacheBytes(memoryClassMb: Int): Int {
        val fifteen = (memoryClassMb.coerceAtLeast(1).toLong() * 1024L * 1024L * 15L) / 100L
        return minOf(fifteen, MEMORY_CACHE_CAP_BYTES).toInt()
    }
}

/**
 * TMDB image URL upgrades. With no API key this still rewrites existing image.tmdb.org URLs.
 * It never calls api.themoviedb.org.
 */
object TmdbArtwork {
    private val sized = Regex(
        """(?i)^(https?://image\.tmdb\.org/t/p/)(w\d+|h\d+|original)/(.+)$"""
    )

    fun posterTarget(): Int = 342
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
        val replacement = if (role == ArtworkRole.POSTER) "w342" else "w780"
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
