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
        episodeIndex: Int? = null,
        durationMs: Long? = null,
        exitCode: Int? = null
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
            if (durationMs != null) {
                append(" durationMs=")
                append(durationMs)
            }
            if (exitCode != null) {
                append(" exitCode=")
                append(exitCode)
            }
        }
    }

    internal const val MAX_STACK_CHARS = 32 * 1024

    /**
     * Full stack text for the debug log. Newlines stay; [note] is a single truncated line
     * and cannot hold a trace.
     */
    fun formatStack(threadName: String, throwable: Throwable): String {
        val buffer = java.io.StringWriter()
        val printer = java.io.PrintWriter(buffer)
        printer.append("uncaught thread=")
        printer.append(threadName)
        printer.println()
        throwable.printStackTrace(printer)
        printer.flush()
        val text = buffer.toString()
        return if (text.length <= MAX_STACK_CHARS) text else text.substring(0, MAX_STACK_CHARS)
    }

    /** Append a stack trace to playback-debug.log. Failures here are swallowed. */
    fun stack(threadName: String, throwable: Throwable) {
        runCatching {
            val dir = AppPaths.configDir
            Files.createDirectories(dir)
            val path = file()
            trimIfLarge(path)
            val body = formatStack(threadName, throwable)
            Files.writeString(
                path,
                Instant.now().toString() + System.lineSeparator() + body,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
            if (!body.endsWith("\n") && !body.endsWith(System.lineSeparator())) {
                Files.writeString(path, System.lineSeparator(), StandardOpenOption.APPEND)
            }
        }
    }

    /** One line in the same playback debug log. Used when Game Day placement fails soft. */
    fun note(message: String) {
        runCatching {
            val dir = AppPaths.configDir
            Files.createDirectories(dir)
            val path = file()
            trimIfLarge(path)
            val line = Instant.now().toString() + " " + message.replace('\n', ' ').take(500)
            Files.writeString(
                path,
                line + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
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
        episodeIndex: Int? = null,
        durationMs: Long? = null,
        exitCode: Int? = null
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
                episodeIndex = episodeIndex,
                durationMs = durationMs,
                exitCode = exitCode
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
