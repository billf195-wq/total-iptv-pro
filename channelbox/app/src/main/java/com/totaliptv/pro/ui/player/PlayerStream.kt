package com.totaliptv.pro.ui.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes

/**
 * HTTP / HLS helpers for the built-in Media3 player.
 *
 * Xtream live HLS (hudv.net and similar) works in VLC because:
 * 1. VLC sends a LibVLC user-agent. Media3's default UA contains "ExoPlayerLib"
 *    and many panels return 403 / empty / HTML for that.
 * 2. The sliding window is only ~5–12s (same as desktop 1.2.10 live stay-up).
 *    A 35s live target offset sits **behind** that window, so ExoPlayer never
 *    starts. VLC just plays the live edge.
 */
object PlayerStream {
    /** Same family VLC Intent uses — panels that accept VLC accept this. */
    const val STREAM_USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"

    const val LIVE_TARGET_OFFSET_MS = 6_000L
    const val LIVE_MIN_OFFSET_MS = 2_000L
    const val LIVE_MAX_OFFSET_MS = 18_000L

    fun isHlsUrl(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    fun isMpegTsUrl(url: String): Boolean =
        url.substringBefore('?').lowercase().endsWith(".ts")

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

    /** Xtream often serves the same stream as MPEG-TS at `{id}.ts`. */
    fun tsFallbackUrl(url: String): String? {
        val qIndex = url.indexOf('?')
        val path = if (qIndex >= 0) url.substring(0, qIndex) else url
        val query = if (qIndex >= 0) url.substring(qIndex) else ""
        if (!path.endsWith(".m3u8", ignoreCase = true)) return null
        return path.dropLast(5) + ".ts" + query
    }

    fun liveTargetOffsetMs(): Long = LIVE_TARGET_OFFSET_MS

    /** Unset = let the playlist drive the live edge (safer than a huge offset). */
    fun unsetLiveOffset(): Long = C.TIME_UNSET
}
