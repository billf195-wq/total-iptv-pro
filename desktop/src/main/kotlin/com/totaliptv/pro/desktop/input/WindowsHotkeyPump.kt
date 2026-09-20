package com.totaliptv.pro.desktop.input

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * RegisterHotKey on a message-only window so Ctrl+Right / Media Next fire while
 * VLC or mpv has exclusive fullscreen focus.
 */
internal class WindowsHotkeyPump private constructor(
    private val thread: Thread,
    private val running: AtomicBoolean,
    private val nativeThreadId: AtomicInteger,
    private val hwndRef: AtomicReference<WinDef.HWND?>
) : AutoCloseable {

    override fun close() {
        running.set(false)
        val tid = nativeThreadId.get()
        if (tid != 0) {
            runCatching {
                User32.INSTANCE.PostThreadMessage(
                    tid,
                    WinUser.WM_QUIT,
                    WinDef.WPARAM(0),
                    WinDef.LPARAM(0)
                )
            }
        }
        hwndRef.get()?.let { hwnd ->
            runCatching {
                User32.INSTANCE.PostMessage(hwnd, WinUser.WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
            }
        }
        thread.interrupt()
        // Do not join here: Quit runs on the EDT. A 1.5s GetMessage join
        // blocked shutdown so the UI never uncomposed and looked like a relaunch.
    }

    companion object {
        private const val ID_CTRL_RIGHT = 0x74A1
        private const val ID_MEDIA_NEXT = 0x74A2
        private const val MOD_CONTROL = 0x0002
        private const val MOD_NOREPEAT = 0x4000
        private const val VK_RIGHT = 0x27
        private const val VK_MEDIA_NEXT_TRACK = 0xB0
        private val HWND_MESSAGE = WinDef.HWND(Pointer.createConstant(-3))

        fun start(onNext: () -> Unit): AutoCloseable? {
            val running = AtomicBoolean(true)
            val registered = AtomicBoolean(false)
            val ready = CountDownLatch(1)
            val nativeThreadId = AtomicInteger(0)
            val hwndRef = AtomicReference<WinDef.HWND?>(null)
            val thread = Thread({
                nativeThreadId.set(Kernel32.INSTANCE.GetCurrentThreadId())
                val user32 = User32.INSTANCE
                val hwnd = runCatching {
                    user32.CreateWindowEx(
                        0,
                        "STATIC",
                        "TotalIptvProSeriesNext",
                        0,
                        0,
                        0,
                        0,
                        0,
                        HWND_MESSAGE,
                        null,
                        null,
                        null
                    )
                }.getOrNull()
                hwndRef.set(hwnd)
                val ctrlOk = user32.RegisterHotKey(hwnd, ID_CTRL_RIGHT, MOD_CONTROL or MOD_NOREPEAT, VK_RIGHT) ||
                    user32.RegisterHotKey(hwnd, ID_CTRL_RIGHT, MOD_CONTROL, VK_RIGHT)
                val mediaOk = user32.RegisterHotKey(hwnd, ID_MEDIA_NEXT, MOD_NOREPEAT, VK_MEDIA_NEXT_TRACK) ||
                    user32.RegisterHotKey(hwnd, ID_MEDIA_NEXT, 0, VK_MEDIA_NEXT_TRACK)
                if (ctrlOk || mediaOk) registered.set(true)
                ready.countDown()
                if (!registered.get()) {
                    running.set(false)
                    if (hwnd != null) runCatching { user32.DestroyWindow(hwnd) }
                    return@Thread
                }
                val msg = WinUser.MSG()
                try {
                    while (running.get()) {
                        val got = user32.GetMessage(msg, null, 0, 0)
                        if (got == 0 || got == -1) break
                        if (msg.message == WinUser.WM_HOTKEY) {
                            onNext()
                            continue
                        }
                        if (msg.message == WinUser.WM_QUIT) break
                        // Do not DispatchMessage — this is a message-only helper, not an
                        // AWT window. Dispatching WM_QUIT/others onto STATIC can keep a
                        // hidden window alive after the UI has quit.
                    }
                } finally {
                    runCatching { user32.UnregisterHotKey(hwnd?.pointer, ID_CTRL_RIGHT) }
                    runCatching { user32.UnregisterHotKey(hwnd?.pointer, ID_MEDIA_NEXT) }
                    if (hwnd != null) runCatching { user32.DestroyWindow(hwnd) }
                    hwndRef.set(null)
                }
            }, "series-next-hotkeys")
            thread.isDaemon = true
            thread.start()
            ready.await(2, TimeUnit.SECONDS)
            if (!registered.get()) return null
            return WindowsHotkeyPump(thread, running, nativeThreadId, hwndRef)
        }
    }
}
