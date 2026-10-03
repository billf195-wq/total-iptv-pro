package com.totaliptv.pro.desktop.player

/**
 * Writes uncaught exceptions to playback-debug.log, then forwards to the previous handler.
 * Installed once from main. A failure inside the log write cannot re-enter this handler.
 */
object UncaughtLog {
    private val writing = ThreadLocal<Boolean>()
    private val gate = Any()

    @Volatile
    private var installed = false
    private var chained: Thread.UncaughtExceptionHandler? = null

    internal var writeStack: (String, Throwable) -> Unit = { threadName, error ->
        PlaybackDebugLog.stack(threadName, error)
    }

    fun install() {
        synchronized(gate) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            chained = previous
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                if (writing.get() == true) {
                    runCatching { previous?.uncaughtException(thread, throwable) }
                    return@setDefaultUncaughtExceptionHandler
                }
                writing.set(true)
                try {
                    writeStack(thread.name, throwable)
                } catch (_: Throwable) {
                } finally {
                    writing.set(false)
                }
                runCatching { previous?.uncaughtException(thread, throwable) }
            }
            installed = true
        }
    }

    internal fun resetForTests() {
        synchronized(gate) {
            if (installed) {
                Thread.setDefaultUncaughtExceptionHandler(chained)
                installed = false
                chained = null
            }
            writeStack = { threadName, error -> PlaybackDebugLog.stack(threadName, error) }
        }
    }
}
