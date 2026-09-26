package com.totaliptv.pro.diagnostics

import android.content.Context
import com.totaliptv.pro.util.SensitiveText
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only log under the app files dir. Playback failures are written here
 * so a quit on device can be read later from Settings, not only logcat.
 */
object DebugLog {
    const val FILE_NAME = "debug.log"
    const val MAX_BYTES = 256 * 1024

    fun file(dir: File): File = File(dir, FILE_NAME)

    fun append(context: Context, tag: String, message: String, error: Throwable? = null) {
        appendTo(context.applicationContext.filesDir, tag, message, error)
    }

    fun appendTo(dir: File, tag: String, message: String, error: Throwable? = null) {
        runCatching {
            dir.mkdirs()
            val line = formatEntry(tag, message, error, System.currentTimeMillis())
            val out = file(dir)
            out.appendText(line)
            trim(out)
        }
    }

    fun read(context: Context, maxChars: Int = 4_000): String {
        val text = readFrom(context.applicationContext.filesDir)
        if (text.length <= maxChars) return text
        return text.takeLast(maxChars)
    }

    fun readFrom(dir: File): String {
        val f = file(dir)
        if (!f.exists()) return ""
        return runCatching { f.readText() }.getOrDefault("")
    }

    fun formatEntry(tag: String, message: String, error: Throwable?, nowMs: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(nowMs))
        val body = buildString {
            append(stamp)
            append(" ")
            append(tag)
            append(": ")
            append(SensitiveText.redact(message))
            if (error != null) {
                append('\n')
                append(SensitiveText.redact(stackTrace(error)))
            }
            append("\n")
        }
        return body
    }

    fun stackTrace(error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun trim(file: File) {
        if (file.length() <= MAX_BYTES) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val keep = text.takeLast(MAX_BYTES / 2)
        file.writeText(keep)
    }
}
