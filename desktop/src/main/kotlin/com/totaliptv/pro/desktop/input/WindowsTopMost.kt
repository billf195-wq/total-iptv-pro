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
    fun raiseWithoutFocus(window: Window) {
        window.isAlwaysOnTop = true
        if (!AppPaths.isWindows) return
        runCatching {
            val hwnd = WinDef.HWND(Native.getComponentPointer(window))
            if (Pointer.nativeValue(hwnd.pointer) == 0L) return
            val topMost = WinDef.HWND(Pointer.createConstant(-1))
            User32.INSTANCE.SetWindowPos(
                hwnd,
                topMost,
                0,
                0,
                0,
                0,
                WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOACTIVATE
            )
        }
    }
}
