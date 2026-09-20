package com.totaliptv.pro.desktop

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide quit. 1.2.4–1.2.6 hosted Series Next as a **second Compose
 * `Window` inside `application { }`**. That sibling stays in Compose Desktop's
 * application window list, so closing the main frame does not end the JVM.
 * The overlay raise path (and Win32 owned-window z-order) then re-showed the
 * main window — a restart loop. Task Manager "Apps" also listed two
 * TotalIptvPro entries (main + "Next episode") for those two windows.
 *
 * Quit therefore:
 *  1. Disposes the overlay AWT window first (not a Compose application Window)
 *  2. Unregisters hotkeys / stops topmost pumps (without blocking the EDT)
 *  3. Stops VLC
 *  4. Calls Compose `exitApplication()`, then [exitProcess] shortly after
 *  5. [Runtime.halt] as last resort if AWT/Compose shutdown hooks deadlock
 *
 * A second Quit while exiting (or within [haltDelayMs]) force-halts so a
 * stuck System.exit cannot loop. Two windows must never keep this process alive.
 */
object AppShutdown {
    private val exiting = AtomicBoolean(false)
    private val firstQuitAtMs = AtomicLong(0)
    private val exitThread = AtomicReference<Thread?>(null)
    private val haltThread = AtomicReference<Thread?>(null)

    /** Test hook — production default is [kotlin.system.exitProcess]. */
    @Volatile
    var forceExit: () -> Unit = { kotlin.system.exitProcess(0) }

    /**
     * Last resort. [Runtime.halt] skips shutdown hooks, which is what we need
     * when AWT `System.exit` deadlocks (EDT inside exit waiting on an AWT hook
     * that `invokeAndWait`s back to the EDT). jpackage then sees a 0 exit and
     * does not respawn the launcher.
     */
    @Volatile
    var haltExit: () -> Unit = { Runtime.getRuntime().halt(0) }

    @Volatile
    var forceExitDelayMs: Long = 400L

    @Volatile
    var haltDelayMs: Long = 2_000L

    fun isExiting(): Boolean = exiting.get()

    /** First caller wins; later callers should halt so a restart loop cannot stick. */
    fun begin(): Boolean {
        val first = exiting.compareAndSet(false, true)
        if (first) firstQuitAtMs.set(System.currentTimeMillis())
        return first
    }

    fun resetForTests() {
        listOf(exitThread, haltThread).forEach { ref ->
            ref.getAndSet(null)?.let { t ->
                t.interrupt()
                runCatching { t.join(250) }
            }
        }
        exiting.set(false)
        firstQuitAtMs.set(0L)
        forceExit = { kotlin.system.exitProcess(0) }
        haltExit = { Runtime.getRuntime().halt(0) }
        forceExitDelayMs = 400L
        haltDelayMs = 2_000L
    }

    fun requestQuit(
        disposeOverlay: () -> Unit,
        stopHotkeys: () -> Unit,
        stopPlayer: () -> Unit,
        stopTopMost: () -> Unit,
        exitApplication: () -> Unit
    ) {
        val first = begin()
        // Overlay first so it cannot re-show the main frame or keep AWT alive.
        runCatching { disposeOverlay() }
        runCatching { stopHotkeys() }
        runCatching { stopPlayer() }
        runCatching { stopTopMost() }
        if (!first) {
            haltExit()
            return
        }
        runCatching { exitApplication() }
        scheduleForceExit()
        scheduleHalt()
    }

    internal fun scheduleForceExit() {
        startNamed("app-shutdown-exit", forceExitDelayMs, exitThread) {
            if (exiting.get()) forceExit()
        }
    }

    internal fun scheduleHalt() {
        startNamed("app-shutdown-halt", haltDelayMs, haltThread) {
            if (exiting.get()) haltExit()
        }
    }

    private fun startNamed(
        name: String,
        delayMs: Long,
        slot: AtomicReference<Thread?>,
        body: () -> Unit
    ) {
        val t = Thread({
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            body()
        }, name)
        slot.set(t)
        t.isDaemon = true
        t.start()
    }

    /** Ctrl/Cmd+Q — the in-app quit key. Alt+F4 still goes through window close. */
    fun isQuitCombo(keyDown: Boolean, ctrlOrMeta: Boolean, isQ: Boolean): Boolean =
        keyDown && ctrlOrMeta && isQ
}
