package com.totaliptv.pro.desktop.input

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RegisterHotKey + PeekMessage on a daemon thread so Ctrl+Right / Media Next
 * work while VLC has exclusive focus.
 */
internal class WindowsHotkeyPump private constructor(
    private val thread: Thread,
    private val running: AtomicBoolean
) : AutoCloseable {

    override fun close() {
        running.set(false)
        thread.interrupt()
        runCatching { thread.join(1500) }
    }

    companion object {
        private const val ID_CTRL_RIGHT = 0x74A1
        private const val ID_MEDIA_NEXT = 0x74A2
        private const val MOD_CONTROL = 0x0002
        private const val MOD_NOREPEAT = 0x4000
        private const val VK_RIGHT = 0x27
        private const val VK_MEDIA_NEXT_TRACK = 0xB0
        private const val PM_REMOVE = 0x0001

        fun start(onNext: () -> Unit): AutoCloseable? {
            val running = AtomicBoolean(true)
            val registered = AtomicBoolean(false)
            val ready = CountDownLatch(1)
            val thread = Thread({
                val user32 = User32.INSTANCE
                val ctrlOk = user32.RegisterHotKey(null, ID_CTRL_RIGHT, MOD_CONTROL or MOD_NOREPEAT, VK_RIGHT) ||
                    user32.RegisterHotKey(null, ID_CTRL_RIGHT, MOD_CONTROL, VK_RIGHT)
                val mediaOk = user32.RegisterHotKey(null, ID_MEDIA_NEXT, MOD_NOREPEAT, VK_MEDIA_NEXT_TRACK) ||
                    user32.RegisterHotKey(null, ID_MEDIA_NEXT, 0, VK_MEDIA_NEXT_TRACK)
                if (ctrlOk || mediaOk) registered.set(true)
                ready.countDown()
                if (!registered.get()) {
                    running.set(false)
                    return@Thread
                }
                val msg = WinUser.MSG()
                try {
                    while (running.get()) {
                        while (user32.PeekMessage(msg, null, 0, 0, PM_REMOVE)) {
                            if (msg.message == WinUser.WM_HOTKEY) {
                                onNext()
                            }
                        }
                        try {
                            Thread.sleep(40)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                } finally {
                    user32.UnregisterHotKey(null, ID_CTRL_RIGHT)
                    user32.UnregisterHotKey(null, ID_MEDIA_NEXT)
                }
            }, "series-next-hotkeys")
            thread.isDaemon = true
            thread.start()
            ready.await(2, TimeUnit.SECONDS)
            if (!registered.get()) return null
            return WindowsHotkeyPump(thread, running)
        }
    }
}
