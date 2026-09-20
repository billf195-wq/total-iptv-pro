package com.totaliptv.pro.desktop.dvr

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Local-device recording folders only. Never a shared NAS / Bigboybill path
 * unless that machine is the one requesting the recording.
 *
 * Windows default: %LOCALAPPDATA%\TotalIptvPro\Recordings
 * Linux default:   ~/Videos/TotalIptvPro/Recordings
 *                  (fallback ~/.local/share/total-iptv-pro/recordings)
 */
object DvrPaths {
    private val stampFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").withZone(ZoneId.systemDefault())

    fun defaultRecordingsDir(
        windows: Boolean,
        userHome: String,
        localAppData: String? = null,
        videosDir: String? = null
    ): Path {
        return if (windows) {
            val local = localAppData?.takeIf { it.isNotBlank() }
                ?: Path.of(userHome, "AppData", "Local").toString()
            Path.of(local, "TotalIptvPro", "Recordings")
        } else {
            val videos = videosDir?.takeIf { it.isNotBlank() }
                ?: Path.of(userHome, "Videos").toString()
            val preferred = Path.of(videos, "TotalIptvPro", "Recordings")
            if (videosDir != null || userHome.isNotBlank()) preferred
            else Path.of(userHome, ".local", "share", "total-iptv-pro", "recordings")
        }
    }

    fun linuxFallbackDir(userHome: String): Path =
        Path.of(userHome, ".local", "share", "total-iptv-pro", "recordings")

    fun resolveRecordingsDir(
        override: String?,
        windows: Boolean,
        userHome: String,
        localAppData: String? = null,
        videosDir: String? = null
    ): Path {
        val custom = override?.trim().orEmpty()
        if (custom.isNotBlank()) return Path.of(custom)
        return defaultRecordingsDir(windows, userHome, localAppData, videosDir)
    }

    fun sanitizeFileName(raw: String, maxLen: Int = 48): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "recording" }
        return cleaned.take(maxLen).trimEnd('.', ' ')
    }

    fun recordingFileName(
        channelName: String,
        title: String,
        startMs: Long,
        extension: String = "ts"
    ): String {
        val stamp = stampFmt.format(Instant.ofEpochMilli(startMs))
        val channel = sanitizeFileName(channelName, 32)
        val show = sanitizeFileName(title, 40)
        val ext = extension.trim().trimStart('.').ifBlank { "ts" }
        return "${channel}_${show}_$stamp.$ext"
    }
}
