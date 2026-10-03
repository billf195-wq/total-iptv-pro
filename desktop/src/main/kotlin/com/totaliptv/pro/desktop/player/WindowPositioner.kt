package com.totaliptv.pro.desktop.player

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.totaliptv.pro.desktop.util.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

object WindowPositioner {

    data class ScreenBounds(val x: Int, val y: Int, val width: Int, val height: Int)

    /** Full monitor rectangle and the taskbar-excluded work area, in physical pixels. */
    data class MonitorRects(val full: ScreenBounds, val work: ScreenBounds)

    @Volatile
    private var appWindow: Window? = null

    private val placedSplitPids = ConcurrentHashMap.newKeySet<Long>()

    /** The Compose frame, so playback can follow the monitor it is on. */
    fun attachAppWindow(window: Window?) {
        appWindow = window
    }

    /**
     * Linux playback target: the monitor that currently holds the app window,
     * or the default screen when that window is not attached yet.
     */
    fun linuxPlaybackMonitor(): ScreenBounds = monitorOrFallback(readAppMonitor(), defaultMonitorBounds())

    /** Monitor the player was placed on: the one holding the app window. */
    fun playbackMonitor(): ScreenBounds = linuxPlaybackMonitor()

    /**
     * Index into [GraphicsEnvironment.getScreenDevices] for Qt
     * `--qt-fullscreen-screennumber`. Java and Qt both list the primary
     * display first on Windows; on Linux this is the same AWT order, so the
     * app window's monitor is the screen VLC fullscreens onto (a saved
     * `[FullScreen] screen` in vlc-qt-interface.conf would otherwise win).
     * Empty list is -1 (omit the flag). A point in a gap uses the nearest screen.
     */
    internal fun qtScreenIndex(centerX: Int, centerY: Int, screens: List<ScreenBounds>): Int {
        if (screens.isEmpty()) return -1
        val hit = screens.indexOfFirst { containsPoint(it, centerX, centerY) }
        if (hit >= 0) return hit
        return screens.indices.minBy { i ->
            val b = screens[i]
            val dx = (b.x + b.width / 2L) - centerX
            val dy = (b.y + b.height / 2L) - centerY
            dx * dx + dy * dy
        }
    }

    /**
     * Screen index of the app window, or 0 when there is only one display
     * and the window is not attached yet. -1 when unknown.
     */
    fun qtFullscreenScreenNumber(): Int {
        return try {
            if (SwingUtilities.isEventDispatchThread()) {
                readQtScreenIndex()
            } else {
                val future = CompletableFuture<Int>()
                SwingUtilities.invokeLater {
                    future.complete(runCatching { readQtScreenIndex() }.getOrDefault(-1))
                }
                future.get(500, TimeUnit.MILLISECONDS)
            }
        } catch (_: Throwable) {
            -1
        }
    }

    private fun readQtScreenIndex(): Int {
        val devices = try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        } catch (_: Throwable) {
            return -1
        }
        if (devices.isEmpty()) return -1
        val window = appWindow
        if (window == null || !window.isDisplayable) {
            return if (devices.size == 1) 0 else -1
        }
        val bounds = window.bounds
        val rects = devices.map { device ->
            val r = device.defaultConfiguration.bounds
            ScreenBounds(r.x, r.y, r.width, r.height)
        }
        return qtScreenIndex(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2, rects)
    }

    /** UI scale of the app window's monitor. Call on the EDT. */
    fun playbackUiScale(): Double {
        val window = appWindow ?: return 1.0
        val scale = try {
            window.graphicsConfiguration?.defaultTransform?.scaleX
        } catch (_: Throwable) {
            null
        }
        return scale?.takeIf { it > 0.0 } ?: 1.0
    }

    internal fun monitorOrFallback(appMonitor: ScreenBounds?, fallback: ScreenBounds): ScreenBounds =
        appMonitor ?: fallback

    /**
     * The monitor whose full bounds contain the app window's center.
     * A window that covers more of another display still follows its center.
     * If the center is in a gap, the nearest monitor is used.
     */
    internal fun monitorContainingCenter(window: ScreenBounds, monitors: List<MonitorRects>): MonitorRects? {
        if (window.width <= 0 || window.height <= 0 || monitors.isEmpty()) return null
        val cx = window.x + window.width / 2
        val cy = window.y + window.height / 2
        monitors.firstOrNull { containsPoint(it.full, cx, cy) }?.let { return it }
        return monitors.minByOrNull { monitor ->
            val mx = monitor.full.x + monitor.full.width / 2
            val my = monitor.full.y + monitor.full.height / 2
            val dx = mx.toLong() - cx
            val dy = my.toLong() - cy
            dx * dx + dy * dy
        }
    }

    internal fun containsPoint(bounds: ScreenBounds, x: Int, y: Int): Boolean =
        x >= bounds.x && y >= bounds.y && x < bounds.x + bounds.width && y < bounds.y + bounds.height

    /** Game Day halves use the work area. Single play covers the full monitor. */
    internal fun splitBoundsFor(monitor: MonitorRects): ScreenBounds = monitor.work

    internal fun fullscreenBoundsFor(monitor: MonitorRects): ScreenBounds = monitor.full

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
     * Work area of the monitor that contains the app window's center.
     * Coordinates are per-monitor v2 physical pixels, the same space the
     * snap loop uses. Falls back to the primary work area.
     */
    fun windowsSplitBounds(): ScreenBounds = readWindowsAppMonitor().work

    /** Borderless-cover the full monitor that holds the app window. */
    fun fullscreenOnAppMonitorAsync(scope: CoroutineScope, process: Process?, pid: Long) {
        if (!AppPaths.isWindows) return
        val full = readWindowsAppMonitor().full
        snapWindowAsync(scope, process, pid, full.x, full.y, full.width, full.height)
    }

    /**
     * Focusing or clicking a Game Day video window selects that side's audio.
     * Startup focus from Windows opening the right window is ignored until
     * both halves have been placed and focus has been still for
     * [LinuxX11WindowPlacer.FOCUS_STABLE_MS]. Arrow keys are not handled here.
     */
    fun watchSplitFocus(scope: CoroutineScope, left: Process, right: Process, onSide: (SplitSide) -> Unit) {
        if (!AppPaths.isWindows) return
        scope.launch(Dispatchers.IO) {
            val focus = LinuxX11WindowPlacer.SplitFocusAudio()
            val leftId = 1L
            val rightId = 2L
            try {
                while (isActive && (left.isAlive || right.isAlive)) {
                    val leftPid = left.pid()
                    val rightPid = right.pid()
                    var side: SplitSide? = null
                    var baseline = 0L
                    withDpiAware {
                        focus.noteBothPlaced(leftPid in placedSplitPids && rightPid in placedSplitPids)
                        val pointer = pointerOnSplit(leftPid, rightPid, leftId, rightId)
                        val clicked = focus.onPointerButton(pointer.first, pointer.second, leftId, rightId)
                        val active = foregroundSplitId(leftPid, rightPid, leftId, rightId)
                        val focused = if (clicked != null) {
                            null
                        } else {
                            focus.onActive(active, leftId, rightId, System.nanoTime() / 1_000_000L)
                        }
                        baseline = focus.consumeBaseline()
                        side = clicked ?: focused
                    }
                    if (baseline != 0L) {
                        PlaybackDebugLog.note("split-win: focus baseline; audio stays on the current side")
                    }
                    val chosen = side
                    if (chosen != null) {
                        PlaybackDebugLog.note("split-win: audio ${chosen.name}")
                        onSide(chosen)
                    }
                    delay(150)
                }
            } catch (t: Throwable) {
                PlaybackDebugLog.note("split-win: audio watch stopped: ${t.message}")
            }
        }
    }

    private fun readWindowsAppMonitor(): MonitorRects {
        if (!AppPaths.isWindows) {
            val bounds = linuxPlaybackMonitor()
            return MonitorRects(bounds, bounds)
        }
        val read = try {
            CompletableFuture.supplyAsync {
                var result: MonitorRects? = null
                withDpiAware { result = monitorForAppWindow() }
                result
            }.get(1500, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            null
        }
        return read ?: primaryMonitor()
    }

    private fun monitorForAppWindow(): MonitorRects? {
        val window = appWindow ?: return primaryMonitor()
        val hwnd = try {
            val ptr = Native.getComponentPointer(window)
            if (ptr == null || Pointer.nativeValue(ptr) == 0L) null else WinDef.HWND(ptr)
        } catch (_: Throwable) {
            null
        } ?: return primaryMonitor()
        val rect = readWindowRect(User32.INSTANCE, hwnd) ?: return primaryMonitor()
        val cx = rect.x + rect.width / 2
        val cy = rect.y + rect.height / 2
        return monitorAt(cx, cy, WinUser.MONITOR_DEFAULTTONEAREST) ?: primaryMonitor()
    }

    private fun primaryMonitor(): MonitorRects {
        return monitorAt(0, 0, WinUser.MONITOR_DEFAULTTOPRIMARY)
            ?: MonitorRects(ScreenBounds(0, 0, 1920, 1080), ScreenBounds(0, 0, 1920, 1040))
    }

    private fun monitorAt(x: Int, y: Int, flags: Int): MonitorRects? {
        val user32 = User32.INSTANCE
        val pt = WinDef.POINT.ByValue()
        pt.x = x
        pt.y = y
        val monitor = user32.MonitorFromPoint(pt, flags) ?: return null
        val info = WinUser.MONITORINFO()
        if (!user32.GetMonitorInfo(monitor, info).booleanValue()) return null
        val full = rectBounds(info.rcMonitor) ?: return null
        val work = rectBounds(info.rcWork) ?: full
        return MonitorRects(full, work)
    }

    private fun rectBounds(rect: WinDef.RECT): ScreenBounds? {
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0 || height <= 0) return null
        return ScreenBounds(rect.left, rect.top, width, height)
    }

    private fun foregroundSplitId(leftPid: Long, rightPid: Long, leftId: Long, rightId: Long): Long {
        val hwnd = User32.INSTANCE.GetForegroundWindow() ?: return 0L
        return when (hwndPid(hwnd)) {
            leftPid -> leftId
            rightPid -> rightId
            else -> 0L
        }
    }

    private fun pointerOnSplit(leftPid: Long, rightPid: Long, leftId: Long, rightId: Long): Pair<Boolean, Long> {
        val down = buttonDown()
        val pt = WinDef.POINT()
        if (!User32.INSTANCE.GetCursorPos(pt)) return down to 0L
        val at = WinDef.POINT.ByValue()
        at.x = pt.x
        at.y = pt.y
        val hwnd = winPoint?.WindowFromPoint(at)
        val id = when (hwnd?.let { hwndPid(it) }) {
            leftPid -> leftId
            rightPid -> rightId
            else -> 0L
        }
        return down to id
    }

    private fun buttonDown(): Boolean {
        val api = User32.INSTANCE
        return listOf(VK_LBUTTON, VK_RBUTTON, VK_MBUTTON).any { key ->
            api.GetAsyncKeyState(key).toInt() and 0x8000 != 0
        }
    }

    private fun hwndPid(hwnd: WinDef.HWND): Long {
        val procId = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, procId)
        return procId.value.toLong() and 0xFFFFFFFFL
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
            if (found) placedSplitPids.add(pid)
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

    private fun readAppMonitor(): ScreenBounds? {
        val window = appWindow ?: return null
        val read = {
            val bounds = try {
                window.graphicsConfiguration?.bounds
            } catch (_: Throwable) {
                null
            }
            if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
                null
            } else {
                ScreenBounds(bounds.x, bounds.y, bounds.width, bounds.height)
            }
        }
        return try {
            if (SwingUtilities.isEventDispatchThread()) {
                read()
            } else {
                var result: ScreenBounds? = null
                SwingUtilities.invokeAndWait { result = read() }
                result
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun defaultMonitorBounds(): ScreenBounds {
        return try {
            val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .defaultConfiguration
                .bounds
            if (bounds.width > 0 && bounds.height > 0) {
                ScreenBounds(bounds.x, bounds.y, bounds.width, bounds.height)
            } else {
                awtWorkArea()
            }
        } catch (_: Throwable) {
            awtWorkArea()
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

    private val winPoint: WinPointLib? by lazy {
        try {
            Native.load("user32", WinPointLib::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    private interface WinPointLib : StdCallLibrary {
        fun WindowFromPoint(point: WinDef.POINT.ByValue): WinDef.HWND?
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
    private const val VK_LBUTTON = 0x01
    private const val VK_RBUTTON = 0x02
    private const val VK_MBUTTON = 0x04

    /** `((DPI_AWARENESS_CONTEXT)-4)` — per-monitor v2. */
    private val DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2: Pointer = Pointer.createConstant(-4)
}
