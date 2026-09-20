package com.totaliptv.pro.desktop

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide quit. Closing the main window while the Windows SeriesNext overlay
 * was still composed used to look like a restart loop: the overlay raise path
 * re-showed a window, AppRoot's host SideEffect restored the overlay session,
 * and Compose Desktop kept the application alive.
 *
 * A real quit tears down overlay + hotkeys + player, then exits the JVM once.
 */
object AppShutdown {
    private val exiting = AtomicBoolean(false)
    private val shutdownThread = AtomicReference<Thread?>(null)

    /** Test hook — production default is [kotlin.system.exitProcess]. */
    @Volatile
    var forceExit: () -> Unit = { kotlin.system.exitProcess(0) }

    @Volatile
    var forceExitDelayMs: Long = 400L

    fun isExiting(): Boolean = exiting.get()

    /** First caller wins; later callers should hard-exit so a restart loop cannot stick. */
    fun begin(): Boolean = exiting.compareAndSet(false, true)

    fun resetForTests() {
        shutdownThread.getAndSet(null)?.let { t ->
            t.interrupt()
            runCatching { t.join(250) }
        }
        exiting.set(false)
        forceExit = { kotlin.system.exitProcess(0) }
        forceExitDelayMs = 400L
    }

    fun requestQuit(
        clearOverlay: () -> Unit,
        stopPlayer: () -> Unit,
        stopHotkeys: () -> Unit,
        stopTopMost: () -> Unit,
        exitApplication: () -> Unit
    ) {
        val first = begin()
        runCatching { clearOverlay() }
        runCatching { stopPlayer() }
        runCatching { stopHotkeys() }
        runCatching { stopTopMost() }
        if (!first) {
            forceExit()
            return
        }
        runCatching { exitApplication() }
        scheduleForceExit()
    }

    internal fun scheduleForceExit() {
        val t = Thread({
            try {
                Thread.sleep(forceExitDelayMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (exiting.get()) forceExit()
        }, "app-shutdown")
        shutdownThread.set(t)
        t.isDaemon = true
        t.start()
    }

    /** Ctrl/Cmd+Q — the in-app quit key. Alt+F4 still goes through window close. */
    fun isQuitCombo(keyDown: Boolean, ctrlOrMeta: Boolean, isQ: Boolean): Boolean =
        keyDown && ctrlOrMeta && isQ
}
