package com.totaliptv.pro.desktop.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.totaliptv.pro.desktop.util.AppPaths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Series skip while an external player owns the screen.
 *
 * Windows registers **Ctrl+Right** and **Media Next** as OS hotkeys (they fire
 * even when VLC is fullscreen). Linux keeps VLC’s own Next (M3U queue) and
 * only handles the same keys when this app’s window is focused.
 */
object SeriesNextHotkeys {
    const val CTRL_RIGHT_HINT = "Ctrl+Right"
    const val MEDIA_NEXT_HINT = "Media Next"

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val nativeStarted = AtomicBoolean(false)
    private val lastFireAtMs = AtomicLong(0)
    private var nativePump: AutoCloseable? = null

    fun addListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        ensureNative()
        return AutoCloseable {
            listeners -= listener
            if (listeners.isEmpty()) stopNative()
        }
    }

    /** Tear down OS hotkeys even if a listener is still registered (real Quit). */
    fun shutdown() {
        listeners.clear()
        stopNative()
    }

    fun requestNext() {
        val fire = {
            val now = System.currentTimeMillis()
            val prev = lastFireAtMs.get()
            if (now - prev >= 500 && lastFireAtMs.compareAndSet(prev, now)) {
                listeners.forEach { runCatching { it.invoke() } }
            }
        }
        if (javax.swing.SwingUtilities.isEventDispatchThread()) fire()
        else javax.swing.SwingUtilities.invokeLater(fire)
    }

    fun isLocalNextKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // Windows OS hotkey already delivers these; handling both would skip twice.
        if (AppPaths.isWindows && nativeStarted.get()) return false
        if (event.isCtrlPressed && event.key == Key.DirectionRight) return true
        // java.awt.event.KeyEvent.VK_MEDIA_NEXT_TRACK — Compose Desktop maps AWT codes 1:1.
        return event.key.keyCode == 0xB0L
    }

    private fun ensureNative() {
        if (!AppPaths.isWindows) return
        if (!nativeStarted.compareAndSet(false, true)) return
        nativePump = runCatching { WindowsHotkeyPump.start { requestNext() } }
            .getOrNull()
        if (nativePump == null) nativeStarted.set(false)
    }

    private fun stopNative() {
        runCatching { nativePump?.close() }
        nativePump = null
        nativeStarted.set(false)
    }
}
