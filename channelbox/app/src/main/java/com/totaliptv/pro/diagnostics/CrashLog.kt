package com.totaliptv.pro.diagnostics

import android.content.Context
import android.content.Intent
import com.totaliptv.pro.util.SensitiveText
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the latest uncaught exception to a file Settings can show and share.
 * The previous handler still runs afterwards, so the system crash dialog is unchanged.
 */
object CrashLog {
    const val FILE_NAME = "last-crash.txt"

    fun file(dir: File): File = File(dir, FILE_NAME)

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Installed && previous.appContext === appContext) return
        val handler = Installed(appContext, previous)
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    fun format(threadName: String, error: Throwable, nowMs: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(nowMs))
        return buildString {
            append("Time: ")
            append(stamp)
            append('\n')
            append("Thread: ")
            append(threadName)
            append('\n')
            append(SensitiveText.redact(DebugLog.stackTrace(error)))
        }
    }

    fun write(context: Context, thread: Thread, error: Throwable) {
        writeTo(context.applicationContext.filesDir, thread.name, error)
    }

    fun writeTo(dir: File, threadName: String, error: Throwable) {
        dir.mkdirs()
        val text = format(threadName, error, System.currentTimeMillis())
        val out = file(dir)
        FileOutputStream(out).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        DebugLog.appendTo(dir, "Crash", "uncaught on $threadName", error)
    }

    fun read(context: Context): String = readFrom(context.applicationContext.filesDir)

    fun readFrom(dir: File): String {
        val f = file(dir)
        if (!f.exists()) return ""
        return runCatching { f.readText() }.getOrDefault("")
    }

    fun share(context: Context): Boolean {
        val text = read(context).trim()
        if (text.isEmpty()) return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Total IPTV Pro last crash")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, "Share last crash")
        if (context !is android.app.Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    private class Installed(
        val appContext: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching { write(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }
}
