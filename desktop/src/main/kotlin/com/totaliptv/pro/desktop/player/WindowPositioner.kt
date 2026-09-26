package com.totaliptv.pro.desktop.player

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD
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
     * Drop [WS_CAPTION] and [WS_THICKFRAME] (and the sysmenu / min / max bits that
     * keep a title bar alive). Bill's live split windows were style `0x9ECF0000`.
     */
    fun borderlessStyle(style: Long): Long {
        val masked = style and 0xFFFFFFFFL
        return masked and (STYLE_CLEAR.toLong().inv() and 0xFFFFFFFFL)
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
     * Stretch a Game Day half to the full height of the monitor that contains it.
     * GNOME may still reserve the top bar; the request is the whole output.
     * The half's x and width stay put so the two pictures still meet.
     */
    fun coverFullMonitorHeight(half: ScreenBounds, monitors: List<ScreenBounds>): ScreenBounds {
        if (monitors.isEmpty() || half.width <= 0 || half.height <= 0) return half
        val cx = half.x + half.width / 2
        val cy = half.y + half.height / 2
        val monitor = monitors.firstOrNull { m ->
            cx >= m.x && cx < m.x + m.width && cy >= m.y && cy < m.y + m.height
        } ?: monitors.firstOrNull { m ->
            half.x >= m.x && half.x < m.x + m.width
        } ?: return half
        if (monitor.height <= 0) return half
        return ScreenBounds(half.x, monitor.y, half.width, monitor.height)
    }

    internal fun awtMonitorBounds(): List<ScreenBounds> {
        return try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
                val bounds = device.defaultConfiguration.bounds
                ScreenBounds(bounds.x, bounds.y, bounds.width, bounds.height)
            }.filter { it.width > 0 && it.height > 0 }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Locks a Game Day window on the visible target rectangle.
     * Windows expands that rectangle by the DWM resize border and strips the
     * caption and thick frame so the two pictures meet at the midpoint.
     * Linux GNOME/XWayland ignores VLC `--video-x` / `--width`, so when DISPLAY
     * is set the X11 placer moves each video window onto its half.
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
        if (!AppPaths.isWindows) {
            LinuxX11WindowPlacer.snapWindowAsync(scope, process, pid, x, y, width, height)
            return
        }
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
        val target = ScreenBounds(x, y, width, height)
        val style = readStyle(user32, hwnd, GWL_STYLE)
        val needsStrip = borderlessStyle(style) != style
        if (!needsStrip && visibleMatches(user32, hwnd, x, y, width, height)) return
        user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
        if (needsStrip) stripDecorations(user32, hwnd)
        // Apply the style with the visible target first (0..1720 and 1720..3440 on
        // Bill's 3440x1440 work area). SWP_FRAMECHANGED is what makes the caption go.
        setWindowRect(user32, hwnd, target)
        // If DWM still reports the 7px resize border, grow the outer rect so the
        // extended frame — the visible part — lands on that same target.
        repeat(2) {
            if (visibleMatches(user32, hwnd, x, y, width, height)) return
            val dpi = dpiFor(hwnd)
            val outer = outerForVisibleTarget(x, y, width, height, usableInsets(measureInsets(user32, hwnd), dpi))
            val current = readWindowRect(user32, hwnd)
            if (current != outer) setWindowRect(user32, hwnd, outer)
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

    private fun stripDecorations(user32: User32, hwnd: WinDef.HWND) {
        val style = readStyle(user32, hwnd, GWL_STYLE)
        val ex = readStyle(user32, hwnd, GWL_EXSTYLE)
        val newStyle = borderlessStyle(style)
        val newEx = ex and (EXSTYLE_CLEAR.toLong().inv() and 0xFFFFFFFFL)
        if (newStyle != style) writeStyle(user32, hwnd, GWL_STYLE, newStyle)
        if (newEx != ex) writeStyle(user32, hwnd, GWL_EXSTYLE, newEx)
    }

    /** Low 32 bits of GetWindowLongPtr. Style `0x9ECF0000` is a captioned thick frame. */
    private fun readStyle(user32: User32, hwnd: WinDef.HWND, index: Int): Long {
        return try {
            val raw: BaseTSD.LONG_PTR = user32.GetWindowLongPtr(hwnd, index)
            raw.toLong() and 0xFFFFFFFFL
        } catch (_: Throwable) {
            user32.GetWindowLong(hwnd, index).toLong() and 0xFFFFFFFFL
        }
    }

    /**
     * SetWindowLongPtr, not SetWindowLong. On 64-bit Windows the style lives in a
     * pointer-sized slot, and SetWindowLongW is not exported.
     */
    private fun writeStyle(user32: User32, hwnd: WinDef.HWND, index: Int, style: Long) {
        val value = BaseTSD.LONG_PTR(style and 0xFFFFFFFFL).toPointer()
        try {
            user32.SetWindowLongPtr(hwnd, index, value)
        } catch (_: Throwable) {
            user32.SetWindowLong(hwnd, index, (style and 0xFFFFFFFFL).toInt())
        }
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
