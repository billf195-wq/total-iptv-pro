package com.totaliptv.pro2.player

import androidx.media3.common.MimeTypes

/**
 * Built-in Media3 helpers. Live Xtream is `{id}.m3u8` in the catalog; ExoPlayer
 * should play `{id}.ts` first (progressive, like VOD).
 */
object PlayerStream {
    const val STREAM_USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"

    const val LIVE_MIN_BUFFER_MS = 5_000
    const val LIVE_MAX_BUFFER_MS = 20_000
    const val LIVE_PLAYBACK_BUFFER_MS = 800
    const val LIVE_REBUFFER_MS = 1_500
    const val LIVE_STUCK_BUFFER_MS = 8_000L

    fun isHlsUrl(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    fun isMpegTsUrl(url: String): Boolean =
        url.substringBefore('?').lowercase().endsWith(".ts")

    fun isLivePath(url: String): Boolean =
        url.substringBefore('?').contains("/live/", ignoreCase = true)

    fun mimeForUrl(url: String): String? {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
            path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    fun splitQuery(url: String): Pair<String, String> {
        val q = url.indexOf('?')
        return if (q >= 0) url.substring(0, q) to url.substring(q) else url to ""
    }

    fun tsUrl(url: String): String? {
        if (isMpegTsUrl(url)) return url
        val (path, query) = splitQuery(url)
        if (!path.endsWith(".m3u8", ignoreCase = true)) return null
        return path.dropLast(5) + ".ts" + query
    }

    fun hlsUrl(url: String): String? {
        if (isHlsUrl(url)) return url
        val (path, query) = splitQuery(url)
        if (!path.endsWith(".ts", ignoreCase = true)) return null
        return path.dropLast(3) + ".m3u8" + query
    }

    fun tsFallbackUrl(url: String): String? = tsUrl(url)?.takeUnless { it == url }

    fun preferredExoUrl(original: String, live: Boolean): String {
        if (!live && !isLivePath(original)) return original
        return tsUrl(original) ?: original
    }

    fun alternateLiveUrl(current: String, original: String): String? {
        val ts = tsUrl(original)
        val hls = hlsUrl(original)
        return when {
            isMpegTsUrl(current) -> hls?.takeIf { it != current }
            isHlsUrl(current) -> ts?.takeIf { it != current }
            else -> ts ?: hls
        }?.takeIf { it != current }
    }
}
