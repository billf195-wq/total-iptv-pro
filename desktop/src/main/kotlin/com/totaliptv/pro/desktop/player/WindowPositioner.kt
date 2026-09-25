package com.totaliptv.pro.desktop.player

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.totaliptv.pro.desktop.util.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment

object WindowPositioner {

    data class ScreenBounds(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * Retrieves the usable screen work area (excluding the Windows taskbar)
     * so windows fit perfectly without overlapping each other or being obscured by the taskbar.
     */
    fun getPrimaryScreenBounds(): ScreenBounds {
        if (AppPaths.isWindows) {
            try {
                val user32 = User32.INSTANCE
                val pt = WinDef.POINT.ByValue()
                pt.x = 0
                pt.y = 0
                val hMonitor = user32.MonitorFromPoint(
                    pt,
                    WinUser.MONITOR_DEFAULTTOPRIMARY
                )
                if (hMonitor != null) {
                    val mi = WinUser.MONITORINFO()
                    if (user32.GetMonitorInfo(hMonitor, mi).booleanValue()) {
                        val rect = mi.rcWork
                        val w = rect.right - rect.left
                        val h = rect.bottom - rect.top
                        if (w > 0 && h > 0) {
                            return ScreenBounds(rect.left, rect.top, w, h)
                        }
                    }
                }
            } catch (_: Throwable) {
                // fallback to AWT
            }
        }
        return try {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val bounds = ge.defaultScreenDevice.defaultConfiguration.bounds
            ScreenBounds(bounds.x, bounds.y, bounds.width, bounds.height)
        } catch (_: Exception) {
            ScreenBounds(0, 0, 1920, 1080)
        }
    }

    /**
     * Continuously positions and locks a player window for split-screen.
     * During the first 12 seconds (while the stream connects, decodes codec, and initializes),
     * it actively enforces the 50% position every 150ms to defeat VLC/player auto-resize.
     * Afterwards, it maintains a steady 1-second check as long as the process is alive.
     */
    fun snapWindowAsync(
        scope: CoroutineScope,
        process: Process?,
        pid: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ) {
        if (!AppPaths.isWindows) return
        scope.launch(Dispatchers.IO) {
            // Phase 1: High frequency snap during launch & stream connection (12 seconds)
            for (i in 0 until 80) {
                if (!isActive) break
                if (process != null && !process.isAlive) break
                snapWindow(pid, x, y, width, height)
                delay(150)
            }

            // Phase 2: Sustained lock while process is alive
            while (isActive) {
                if (process != null && !process.isAlive) break
                delay(1000)
                ensureWindowPosition(pid, x, y, width, height)
            }
        }
    }

    fun snapWindow(pid: Long, x: Int, y: Int, width: Int, height: Int): Boolean {
        if (!AppPaths.isWindows) return false
        return try {
            val user32 = User32.INSTANCE
            var found = false
            user32.EnumWindows({ hwnd, _ ->
                if (user32.IsWindowVisible(hwnd)) {
                    val procId = com.sun.jna.ptr.IntByReference()
                    user32.GetWindowThreadProcessId(hwnd, procId)
                    if (procId.value.toLong() == pid) {
                        val owner = user32.GetWindow(hwnd, WinDef.DWORD(WinUser.GW_OWNER.toLong()))
                        val parent = user32.GetParent(hwnd)
                        // Target only the top-level main window (not dialogs, tooltips, or popups)
                        if (owner == null && parent == null) {
                            user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
                            user32.SetWindowPos(
                                hwnd,
                                WinDef.HWND(Pointer.createConstant(0)),
                                x, y, width, height,
                                WinUser.SWP_SHOWWINDOW or WinUser.SWP_NOZORDER or 0x0020 // SWP_FRAMECHANGED
                            )
                            found = true
                        }
                    }
                }
                true
            }, null)
            found
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensureWindowPosition(pid: Long, x: Int, y: Int, width: Int, height: Int) {
        try {
            val user32 = User32.INSTANCE
            user32.EnumWindows({ hwnd, _ ->
                if (user32.IsWindowVisible(hwnd)) {
                    val procId = com.sun.jna.ptr.IntByReference()
                    user32.GetWindowThreadProcessId(hwnd, procId)
                    if (procId.value.toLong() == pid) {
                        val owner = user32.GetWindow(hwnd, WinDef.DWORD(WinUser.GW_OWNER.toLong()))
                        val parent = user32.GetParent(hwnd)
                        if (owner == null && parent == null) {
                            val rect = WinDef.RECT()
                            user32.GetWindowRect(hwnd, rect)
                            val curW = rect.right - rect.left
                            val curH = rect.bottom - rect.top
                            if (rect.left != x || rect.top != y || curW != width || curH != height) {
                                user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
                                user32.SetWindowPos(
                                    hwnd,
                                    WinDef.HWND(Pointer.createConstant(0)),
                                    x, y, width, height,
                                    WinUser.SWP_SHOWWINDOW or WinUser.SWP_NOZORDER or 0x0020
                                )
                            }
                        }
                    }
                }
                true
            }, null)
        } catch (_: Throwable) {}
    }
}
