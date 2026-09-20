package com.totaliptv.pro.desktop.player

import com.totaliptv.pro.desktop.AppVersion
import com.totaliptv.pro.desktop.util.AppPaths
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * One-line launch log under the app config dir so Bigboybill can confirm
 * which episode URL and player actually started.
 *
 * Windows: %APPDATA%\total-iptv-pro\playback-debug.log
 * Linux: ~/.config/total-iptv-pro/playback-debug.log
 */
object PlaybackDebugLog {
    const val FILE_NAME = "playback-debug.log"
    internal const val MAX_BYTES = 256 * 1024

    fun file(): Path = AppPaths.configDir.resolve(FILE_NAME)

    fun redactStreamUrl(url: String): String {
        val raw = url.trim()
        if (raw.isBlank()) return "-"
        return try {
            val uri = URI(raw)
            val host = buildString {
                append(uri.host ?: "no-host")
                if (uri.port > 0) {
                    append(':')
                    append(uri.port)
                }
            }
            val tail = uri.path.orEmpty()
                .split('/')
                .filter { it.isNotBlank() }
                .lastOrNull()
                ?: uri.path.orEmpty().takeLast(48).ifBlank { "-" }
            "$host/.../$tail"
        } catch (_: Exception) {
            val cut = raw.substringAfter("://", raw)
            val host = cut.substringBefore('/')
            val tail = cut.substringAfterLast('/', missingDelimiterValue = cut).take(64)
            "$host/.../$tail"
        }
    }

    fun formatLine(
        episodeId: String?,
        season: Int?,
        episodeNum: Int?,
        streamUrl: String,
        playerBinary: String,
        windows: Boolean,
        playlist: Boolean,
        reason: String,
        episodeCount: Int? = null,
        episodeIndex: Int? = null
    ): String {
        val sxex = when {
            season != null && episodeNum != null -> "S${season}E${episodeNum}"
            else -> "S?E?"
        }
        return buildString {
            append(Instant.now())
            append(" v=")
            append(AppVersion.VERSION_NAME)
            append(" reason=")
            append(reason.ifBlank { "play" })
            append(" episodeId=")
            append(episodeId?.trim()?.ifBlank { "-" } ?: "-")
            append(' ')
            append(sxex)
            append(" url=")
            append(redactStreamUrl(streamUrl))
            append(" player=")
            append(playerBinary.ifBlank { "-" })
            append(" windows=")
            append(windows)
            append(" playlist=")
            append(playlist)
            if (episodeCount != null) {
                append(" eps=")
                append(episodeCount)
            }
            if (episodeIndex != null) {
                append(" idx=")
                append(episodeIndex)
            }
        }
    }

    fun record(
        episodeId: String?,
        season: Int?,
        episodeNum: Int?,
        streamUrl: String,
        playerBinary: String,
        windows: Boolean,
        playlist: Boolean,
        reason: String,
        episodeCount: Int? = null,
        episodeIndex: Int? = null
    ) {
        runCatching {
            val dir = AppPaths.configDir
            Files.createDirectories(dir)
            val path = file()
            trimIfLarge(path)
            val line = formatLine(
                episodeId = episodeId,
                season = season,
                episodeNum = episodeNum,
                streamUrl = streamUrl,
                playerBinary = playerBinary,
                windows = windows,
                playlist = playlist,
                reason = reason,
                episodeCount = episodeCount,
                episodeIndex = episodeIndex
            )
            Files.writeString(
                path,
                line + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }
    }

    private fun trimIfLarge(path: Path) {
        if (!Files.isRegularFile(path)) return
        if (Files.size(path) < MAX_BYTES) return
        runCatching { Files.deleteIfExists(path) }
    }
}
