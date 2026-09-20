package com.totaliptv.pro.desktop.dvr

/**
 * Recording source: live capture vs finite VOD/series download.
 */
object DvrKind {
    const val LIVE = "LIVE"
    const val VOD = "VOD"
    const val SERIES = "SERIES"

    fun normalize(raw: String?): String =
        when (raw?.trim()?.uppercase()) {
            VOD, "MOVIE", "MOVIES" -> VOD
            SERIES, "EPISODE" -> SERIES
            else -> LIVE
        }

    fun label(kind: String): String = when (normalize(kind)) {
        VOD -> "Movie"
        SERIES -> "Series"
        else -> "Live"
    }

    fun isFiniteDownload(kind: String, url: String): Boolean {
        if (normalize(kind) == LIVE) return false
        val path = urlPath(url)
        if (path.contains("/live/")) return false
        if (path.contains("/movie/") || path.contains("/series/")) return true
        return normalize(kind) == VOD || normalize(kind) == SERIES
    }

    fun extensionForUrl(url: String, kind: String = LIVE): String {
        val path = urlPath(url)
        return when {
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "mp4"
            path.endsWith(".mkv") -> "mkv"
            path.endsWith(".avi") -> "avi"
            path.endsWith(".ts") -> "ts"
            isFiniteDownload(kind, url) -> "mp4"
            else -> "ts"
        }
    }

    private fun urlPath(url: String): String {
        val raw = url.trim()
        val noQuery = raw.substringBefore('?')
        return try {
            java.net.URI(noQuery).path.orEmpty().ifBlank { noQuery }
        } catch (_: Exception) {
            noQuery
        }.lowercase()
    }
}
