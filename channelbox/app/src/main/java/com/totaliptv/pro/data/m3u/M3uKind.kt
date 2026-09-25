package com.totaliptv.pro.data.m3u

/**
 * M3U series vs live. The word "series" inside a channel name or group
 * ("World Series", "NASCAR Cup Series") is not enough — that pulled live
 * sports out of Live TV. Series means an explicit group/type or a `/series/` URL path.
 */
object M3uKind {
    fun isSeries(url: String, group: String, typeAttr: String? = null): Boolean {
        if (typeAttr.equals("series", ignoreCase = true)) return true
        if (urlPath(url).contains("/series/")) return true
        return explicitSeriesGroup(group)
    }

    fun isVod(url: String, group: String, name: String = ""): Boolean {
        if (urlPath(url).contains("/movie/")) return true
        val g = group.trim().lowercase()
        if (g == "movie" || g == "movies" || g == "vod" || g == "films" || g == "film") return true
        if (g.startsWith("movie ") || g.startsWith("movies ") || g.startsWith("vod ") ||
            g.startsWith("vod|") || g.startsWith("movie|")
        ) {
            return true
        }
        val hay = "$url $group $name".lowercase()
        return hay.contains("movie") || hay.contains("vod") || hay.contains(".mp4") || hay.contains(".mkv")
    }

    private fun explicitSeriesGroup(group: String): Boolean {
        val g = group.trim()
        if (g.isEmpty()) return false
        if (g.equals("series", ignoreCase = true) ||
            g.equals("tv series", ignoreCase = true) ||
            g.equals("tv shows", ignoreCase = true)
        ) {
            return true
        }
        if (!g.startsWith("series", ignoreCase = true)) return false
        if (g.length == 6) return true
        val next = g[6]
        return next == '|' || next == ':' || next == '/' || next == '-' || next == '–' || next == ' '
    }

    private fun urlPath(url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return ""
        return try {
            java.net.URI(raw).rawPath?.lowercase().orEmpty()
        } catch (_: Exception) {
            raw.lowercase()
        }
    }
}
