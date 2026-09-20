package com.totaliptv.pro.desktop.input

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.totaliptv.pro.desktop.AppShutdown
import com.totaliptv.pro.desktop.util.AppPaths
import java.awt.Window
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Keep the Next-episode overlay above fullscreen VLC without stealing focus
 * and without blocking the Compose/EDT thread.
 *
 * The 1.2.4 raise loop called [Window.setAlwaysOnTop] + SetWindowPos(SWP_SHOWWINDOW)
 * every 400ms on the EDT. That:
 *  - deadlocked / froze the UI when VLC left fullscreen (DWM z-order lock)
 *  - re-showed the overlay (and its owner, the main window) after Quit
 */
internal object WindowsTopMost {
    private const val GWL_EXSTYLE = -20
    private const val WS_EX_TOPMOST = 0x00000008
    private const val WS_EX_TOOLWINDOW = 0x00000080

    const val SWP_NOSIZE = 0x0001
    const val SWP_NOMOVE = 0x0002
    const val SWP_NOACTIVATE = 0x0010
    const val SWP_SHOWWINDOW = 0x0040
    const val SWP_NOOWNERZORDER = 0x0200
    const val SWP_ASYNCWINDOWPOS = 0x4000

    /**
     * Z-order only. Never [SWP_SHOWWINDOW] — that flag re-showed windows after close.
     * [SWP_ASYNCWINDOWPOS] so SetWindowPos does not wait on VLC's UI thread.
     */
    const val RAISE_FLAGS: Int =
        SWP_NOSIZE or SWP_NOMOVE or SWP_NOACTIVATE or SWP_NOOWNERZORDER or SWP_ASYNCWINDOWPOS

    private val pumps = CopyOnWriteArrayList<RaisePump>()

    fun raiseFlagsIncludeShowWindow(): Boolean = (RAISE_FLAGS and SWP_SHOWWINDOW) != 0

    fun startRaisePump(window: Window): AutoCloseable {
        if (!AppPaths.isWindows) {
            runCatching { window.isAlwaysOnTop = true }
            return AutoCloseable { }
        }
        val hwndRef = AtomicReference<WinDef.HWND?>(null)
        runCatching {
            window.isAlwaysOnTop = true
            val ptr = Native.getComponentPointer(window)
            if (ptr != null && Pointer.nativeValue(ptr) != 0L) {
                hwndRef.set(WinDef.HWND(ptr))
            }
        }
        val pump = RaisePump(hwndRef)
        pumps += pump
        pump.start()
        return AutoCloseable {
            pump.close()
            pumps -= pump
        }
    }

    fun shutdown() {
        pumps.forEach { runCatching { it.close() } }
        pumps.clear()
    }

    internal fun raiseHwnd(hwnd: WinDef.HWND) {
        if (Pointer.nativeValue(hwnd.pointer) == 0L) return
        val user32 = User32.INSTANCE
        val old = user32.GetWindowLong(hwnd, GWL_EXSTYLE)
        user32.SetWindowLong(hwnd, GWL_EXSTYLE, old or WS_EX_TOPMOST or WS_EX_TOOLWINDOW)
        val topMost = WinDef.HWND(Pointer.createConstant(-1))
        user32.SetWindowPos(
            hwnd,
            topMost,
            0,
            0,
            0,
            0,
            RAISE_FLAGS
        )
    }

    private class RaisePump(
        private val hwndRef: AtomicReference<WinDef.HWND?>
    ) : AutoCloseable {
        private val running = AtomicBoolean(true)
        private val thread = Thread({
            while (running.get() && !AppShutdown.isExiting()) {
                val hwnd = hwndRef.get()
                if (hwnd != null) {
                    runCatching { raiseHwnd(hwnd) }
                }
                try {
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "series-next-topmost")

        fun start() {
            thread.isDaemon = true
            thread.start()
        }

        override fun close() {
            running.set(false)
            thread.interrupt()
            runCatching { thread.join(500) }
        }
    }
}
