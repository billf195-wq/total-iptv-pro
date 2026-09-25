package com.totaliptv.pro.desktop.player

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import com.totaliptv.pro.desktop.util.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object WindowPositioner {

    data class ScreenBounds(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * Extra pixels Windows 10/11 DWM adds outside the visible frame.
     * [left]/[right]/[bottom] are the invisible resize borders (about 7px at 96 DPI).
     * [top] is often 0 once the caption is gone.
     */
    data class FrameInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * Left half is `[x, x + width/2)`, right half is the remainder through `x + width`,
     * so an odd width does not leave a 1px column between the pictures.
     */
    fun splitHalves(bounds: ScreenBounds): Pair<ScreenBounds, ScreenBounds> {
        val leftWidth = bounds.width / 2
        val rightWidth = bounds.width - leftWidth
        val left = ScreenBounds(bounds.x, bounds.y, leftWidth, bounds.height)
        val right = ScreenBounds(bounds.x + leftWidth, bounds.y, rightWidth, bounds.height)
        return left to right
    }

    /** Outer rect whose visible frame (after DWM insets) is exactly [x, y, width, height]. */
    fun outerForVisibleTarget(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        insets: FrameInsets
    ): ScreenBounds {
        return ScreenBounds(
            x - insets.left,
            y - insets.top,
            width + insets.left + insets.right,
            height + insets.top + insets.bottom
        )
    }

    /** `visible` is the DWM extended frame; `outer` is [GetWindowRect]. */
    fun insetsBetween(outer: ScreenBounds, visible: ScreenBounds): FrameInsets {
        val outerRight = outer.x + outer.width
        val outerBottom = outer.y + outer.height
        val visibleRight = visible.x + visible.width
        val visibleBottom = visible.y + visible.height
        return FrameInsets(
            left = visible.x - outer.x,
            top = visible.y - outer.y,
            right = outerRight - visibleRight,
            bottom = outerBottom - visibleBottom
        )
    }

    /** Invisible resize border at 96 DPI is 7px; scale with the monitor DPI. */
    fun resizeBorderPx(dpi: Int): Int {
        val safe = if (dpi <= 0) 96 else dpi
        return ((7 * safe) + 48) / 96
    }

    fun fallbackFrameInsets(dpi: Int): FrameInsets {
        val px = resizeBorderPx(dpi)
        return FrameInsets(left = px, top = 0, right = px, bottom = px)
    }

    /**
     * Keep a measured inset only when every side is a DWM margin, not a title bar.
     * Anything else falls back to the DPI-scaled 7px border.
     */
    fun usableInsets(measured: FrameInsets?, dpi: Int): FrameInsets {
        val fallback = fallbackFrameInsets(dpi)
        if (measured == null) return fallback
        val max = (resizeBorderPx(dpi) * 4).coerceAtLeast(16)
        val sides = listOf(measured.left, measured.top, measured.right, measured.bottom)
        if (sides.any { it < 0 || it > max }) return fallback
        return measured
    }

    /**
     * Usable work area of the primary monitor (taskbar excluded).
     * Windows uses the Win32 work rectangle in per-monitor DPI coordinates.
     * Linux uses the screen bounds minus toolkit insets, which is the full
     * monitor when the desktop reports no panel.
     */
    fun getPrimaryScreenBounds(): ScreenBounds {
        if (AppPaths.isWindows) {
            val win = try {
                CompletableFuture.supplyAsync {
                    var result: ScreenBounds? = null
                    withDpiAware { result = win32WorkArea() }
                    result
                }.get(1500, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                null
            }
            if (win != null) return win
        }
        return awtWorkArea()
    }

    /**
     * Locks a Game Day window on the visible target rectangle.
     * Windows expands that rectangle by the DWM resize border and strips the
     * caption and thick frame so the two pictures meet at the midpoint.
     * Linux has no DWM inset; VLC `--video-x` / `--width` / `--no-video-deco`
     * already place the undecorated windows on [splitHalves].
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
            for (i in 0 until 80) {
                if (!isActive) break
                if (process != null && !process.isAlive) break
                snapWindow(pid, x, y, width, height)
                delay(150)
            }
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
            var found = false
            withDpiAware { found = placeProcessWindows(pid, x, y, width, height) }
            found
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensureWindowPosition(pid: Long, x: Int, y: Int, width: Int, height: Int) {
        try {
            withDpiAware { placeProcessWindows(pid, x, y, width, height) }
        } catch (_: Throwable) {
        }
    }

    private fun awtWorkArea(): ScreenBounds {
        return try {
            val device = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .defaultConfiguration
            val bounds = device.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(device)
            val width = (bounds.width - insets.left - insets.right).coerceAtLeast(1)
            val height = (bounds.height - insets.top - insets.bottom).coerceAtLeast(1)
            ScreenBounds(bounds.x + insets.left, bounds.y + insets.top, width, height)
        } catch (_: Exception) {
            ScreenBounds(0, 0, 1920, 1080)
        }
    }

    private fun win32WorkArea(): ScreenBounds? {
        val user32 = User32.INSTANCE
        val pt = WinDef.POINT.ByValue()
        pt.x = 0
        pt.y = 0
        val monitor = user32.MonitorFromPoint(pt, WinUser.MONITOR_DEFAULTTOPRIMARY) ?: return null
        val info = WinUser.MONITORINFO()
        if (!user32.GetMonitorInfo(monitor, info).booleanValue()) return null
        val rect = info.rcWork
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0 || height <= 0) return null
        return ScreenBounds(rect.left, rect.top, width, height)
    }

    private fun placeProcessWindows(pid: Long, x: Int, y: Int, width: Int, height: Int): Boolean {
        val user32 = User32.INSTANCE
        val windows = visibleWindowsForPid(user32, pid)
        if (windows.isEmpty()) return false
        val video = windows.filter { isVideoOutputWindow(windowClass(user32, it)) }
        val place = if (video.isNotEmpty()) {
            video
        } else {
            windows.filter { isTopLevel(user32, it) }.ifEmpty { windows }
        }
        val placeIds = place.map { hwndValue(it) }.toSet()
        val park = if (video.isNotEmpty()) windows.filter { hwndValue(it) !in placeIds } else emptyList()
        for (hwnd in place) {
            positionBorderless(user32, hwnd, x, y, width, height)
        }
        for (hwnd in park) {
            parkOffScreen(user32, hwnd)
        }
        return true
    }

    private fun positionBorderless(
        user32: User32,
        hwnd: WinDef.HWND,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ) {
        user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
        val stripped = stripDecorations(user32, hwnd)
        if (!stripped && visibleMatches(user32, hwnd, x, y, width, height)) return
        val dpi = dpiFor(hwnd)
        val outer = outerForVisibleTarget(x, y, width, height, usableInsets(measureInsets(user32, hwnd), dpi))
        setWindowRect(user32, hwnd, outer)
        val corrected = outerForVisibleTarget(x, y, width, height, usableInsets(measureInsets(user32, hwnd), dpi))
        if (corrected != outer || !visibleMatches(user32, hwnd, x, y, width, height)) {
            setWindowRect(user32, hwnd, corrected)
        }
    }

    private fun visibleMatches(
        user32: User32,
        hwnd: WinDef.HWND,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Boolean {
        val extended = readExtendedFrame(hwnd)
        if (extended != null) {
            return extended.x == x && extended.y == y && extended.width == width && extended.height == height
        }
        val window = readWindowRect(user32, hwnd) ?: return false
        val expected = outerForVisibleTarget(x, y, width, height, fallbackFrameInsets(dpiFor(hwnd)))
        return window == expected
    }

    private fun parkOffScreen(user32: User32, hwnd: WinDef.HWND) {
        user32.SetWindowPos(
            hwnd,
            WinDef.HWND(Pointer.createConstant(0)),
            -32000,
            -32000,
            100,
            100,
            WinUser.SWP_NOZORDER or SWP_NOACTIVATE
        )
    }

    private fun setWindowRect(user32: User32, hwnd: WinDef.HWND, rect: ScreenBounds) {
        user32.SetWindowPos(
            hwnd,
            WinDef.HWND(Pointer.createConstant(0)),
            rect.x,
            rect.y,
            rect.width,
            rect.height,
            WinUser.SWP_SHOWWINDOW or WinUser.SWP_NOZORDER or SWP_FRAMECHANGED or SWP_NOACTIVATE
        )
    }

    private fun measureInsets(user32: User32, hwnd: WinDef.HWND): FrameInsets? {
        val outer = readWindowRect(user32, hwnd) ?: return null
        val visible = readExtendedFrame(hwnd) ?: return null
        if (visible.width <= 0 || visible.height <= 0) return null
        return insetsBetween(outer, visible)
    }

    private fun readWindowRect(user32: User32, hwnd: WinDef.HWND): ScreenBounds? {
        val rect = WinDef.RECT()
        if (!user32.GetWindowRect(hwnd, rect)) return null
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0 || height <= 0) return null
        return ScreenBounds(rect.left, rect.top, width, height)
    }

    private fun readExtendedFrame(hwnd: WinDef.HWND): ScreenBounds? {
        val dwm = dwmApi ?: return null
        val rect = WinDef.RECT()
        val hr = try {
            dwm.DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, rect, 16)
        } catch (_: Throwable) {
            return null
        }
        if (hr != 0) return null
        rect.read()
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0 || height <= 0) return null
        return ScreenBounds(rect.left, rect.top, width, height)
    }

    private fun dpiFor(hwnd: WinDef.HWND): Int {
        val api = dpiUser32 ?: return 96
        return try {
            val dpi = api.GetDpiForWindow(hwnd)
            if (dpi <= 0) 96 else dpi
        } catch (_: Throwable) {
            96
        }
    }

    private fun stripDecorations(user32: User32, hwnd: WinDef.HWND): Boolean {
        val style = user32.GetWindowLong(hwnd, GWL_STYLE)
        val ex = user32.GetWindowLong(hwnd, GWL_EXSTYLE)
        val newStyle = style and STYLE_CLEAR.inv()
        val newEx = ex and EXSTYLE_CLEAR.inv()
        var changed = false
        if (newStyle != style) {
            user32.SetWindowLong(hwnd, GWL_STYLE, newStyle)
            changed = true
        }
        if (newEx != ex) {
            user32.SetWindowLong(hwnd, GWL_EXSTYLE, newEx)
            changed = true
        }
        return changed
    }

    private fun visibleWindowsForPid(user32: User32, pid: Long): List<WinDef.HWND> {
        val found = mutableListOf<WinDef.HWND>()
        user32.EnumWindows({ hwnd, _ ->
            if (user32.IsWindowVisible(hwnd)) {
                val procId = com.sun.jna.ptr.IntByReference()
                user32.GetWindowThreadProcessId(hwnd, procId)
                if (procId.value.toLong() == pid) found += hwnd
            }
            true
        }, null)
        return found
    }

    private fun isTopLevel(user32: User32, hwnd: WinDef.HWND): Boolean {
        val owner = user32.GetWindow(hwnd, WinDef.DWORD(WinUser.GW_OWNER.toLong()))
        val parent = user32.GetParent(hwnd)
        return isNullHwnd(owner) && isNullHwnd(parent)
    }

    private fun isNullHwnd(hwnd: WinDef.HWND?): Boolean {
        if (hwnd == null) return true
        val pointer = hwnd.pointer ?: return true
        return Pointer.nativeValue(pointer) == 0L
    }

    private fun hwndValue(hwnd: WinDef.HWND): Long {
        val pointer = hwnd.pointer ?: return 0L
        return Pointer.nativeValue(pointer)
    }

    private fun windowClass(user32: User32, hwnd: WinDef.HWND): String {
        val buf = CharArray(256)
        val n = user32.GetClassName(hwnd, buf, buf.size)
        if (n <= 0) return ""
        return String(buf, 0, n.coerceAtMost(buf.size))
    }

    private fun isVideoOutputWindow(className: String): Boolean {
        return className.equals("VLC video output", ignoreCase = true) ||
            className.contains("video output", ignoreCase = true)
    }

    /**
     * Per-monitor v2 coordinates match VLC and the real pixels of a scaled display.
     * The calling thread is restored so the Compose UI thread is left alone.
     */
    private fun withDpiAware(block: () -> Unit) {
        if (!AppPaths.isWindows) {
            block()
            return
        }
        val api = dpiUser32
        if (api == null) {
            block()
            return
        }
        val previous = try {
            api.SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2)
        } catch (_: Throwable) {
            null
        }
        try {
            block()
        } finally {
            if (previous != null) {
                try {
                    api.SetThreadDpiAwarenessContext(previous)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private val dpiUser32: DpiUser32? by lazy {
        try {
            Native.load("user32", DpiUser32::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    private val dwmApi: DwmApiLib? by lazy {
        try {
            Native.load("dwmapi", DwmApiLib::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    private interface DpiUser32 : StdCallLibrary {
        fun SetThreadDpiAwarenessContext(context: Pointer): Pointer?
        fun GetDpiForWindow(hwnd: WinDef.HWND): Int
    }

    private interface DwmApiLib : StdCallLibrary {
        fun DwmGetWindowAttribute(
            hwnd: WinDef.HWND,
            dwAttribute: Int,
            pvAttribute: WinDef.RECT,
            cbAttribute: Int
        ): Int
    }

    private const val GWL_STYLE = -16
    private const val GWL_EXSTYLE = -20
    private const val WS_CAPTION = 0x00C00000
    private const val WS_THICKFRAME = 0x00040000
    private const val WS_BORDER = 0x00800000
    private const val WS_DLGFRAME = 0x00400000
    private const val WS_SYSMENU = 0x00080000
    private const val WS_MINIMIZEBOX = 0x00020000
    private const val WS_MAXIMIZEBOX = 0x00010000
    private const val STYLE_CLEAR =
        WS_CAPTION or WS_THICKFRAME or WS_BORDER or WS_DLGFRAME or
            WS_SYSMENU or WS_MINIMIZEBOX or WS_MAXIMIZEBOX
    private const val WS_EX_DLGMODALFRAME = 0x00000001
    private const val WS_EX_CLIENTEDGE = 0x00000200
    private const val WS_EX_STATICEDGE = 0x00020000
    private const val WS_EX_WINDOWEDGE = 0x00000100
    private const val EXSTYLE_CLEAR =
        WS_EX_DLGMODALFRAME or WS_EX_CLIENTEDGE or WS_EX_STATICEDGE or WS_EX_WINDOWEDGE
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020
    private const val DWMWA_EXTENDED_FRAME_BOUNDS = 9

    /** `((DPI_AWARENESS_CONTEXT)-4)` — per-monitor v2. */
    private val DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2: Pointer = Pointer.createConstant(-4)
}
