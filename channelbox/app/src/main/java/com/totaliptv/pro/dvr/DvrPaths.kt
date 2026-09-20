package com.totaliptv.pro.dvr

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Local-device recording folders for Android TV / phone.
 * Never a shared NAS path unless this device is the one recording.
 *
 * Default: app-specific Movies/TotalIptvPro/Recordings on **this** device
 * (getExternalFilesDir(Movies)), else filesDir/recordings.
 */
object DvrPaths {
    private val stampFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").withZone(ZoneId.systemDefault())

    fun defaultRecordingsDir(
        filesDir: String,
        externalMoviesDir: String? = null,
        publicMoviesDir: String? = null
    ): String {
        val external = externalMoviesDir?.trim().orEmpty()
        if (external.isNotBlank()) {
            return join(external, "TotalIptvPro", "Recordings")
        }
        val pub = publicMoviesDir?.trim().orEmpty()
        if (pub.isNotBlank()) {
            return join(pub, "TotalIptvPro", "Recordings")
        }
        return join(filesDir, "recordings")
    }

    fun resolveRecordingsDir(
        override: String?,
        filesDir: String,
        externalMoviesDir: String? = null,
        publicMoviesDir: String? = null
    ): String {
        val custom = override?.trim().orEmpty()
        if (custom.isNotBlank()) return custom
        return defaultRecordingsDir(filesDir, externalMoviesDir, publicMoviesDir)
    }

    fun metadataDir(filesDir: String): String = join(filesDir, "dvr")

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

    private fun join(first: String, vararg more: String): String {
        var out = first.trimEnd('/', '\\')
        for (part in more) {
            out = "$out/${part.trim('/', '\\')}"
        }
        return out
    }
}
