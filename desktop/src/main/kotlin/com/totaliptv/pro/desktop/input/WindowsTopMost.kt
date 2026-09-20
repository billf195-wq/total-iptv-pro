package com.totaliptv.pro.desktop.input

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.totaliptv.pro.desktop.util.AppPaths
import java.awt.Window

/** Keep the Next-episode overlay above fullscreen VLC without stealing focus. */
internal object WindowsTopMost {
    private const val GWL_EXSTYLE = -20
    private const val WS_EX_TOPMOST = 0x00000008
    private const val WS_EX_TOOLWINDOW = 0x00000080

    fun raiseWithoutFocus(window: Window) {
        window.isAlwaysOnTop = true
        if (!AppPaths.isWindows) return
        runCatching {
            val hwnd = WinDef.HWND(Native.getComponentPointer(window))
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
                WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOACTIVATE or WinUser.SWP_SHOWWINDOW
            )
        }
    }
}
