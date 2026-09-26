package com.totaliptv.pro.desktop.player

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import com.totaliptv.pro.desktop.player.WindowPositioner.ScreenBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Places Game Day VLC video windows on Linux/XWayland.
 *
 * GNOME ignores `--video-x` / `--width`. Each VLC (launched with `--intf=dummy`)
 * has an xcb video window; this mover drops maximized state, asks for no Motif
 * decorations, and [XMoveResizeWindow]s it onto its half. Failures are logged
 * to [PlaybackDebugLog] and never thrown.
 *
 * The window is matched to the VLC pid with XRes (`XResQueryClientIds` on
 * libXRes.so.1). If that library is missing, `_NET_WM_PID` is used instead.
 */
object LinuxX11WindowPlacer {
    private const val CLIENT_MESSAGE = 33
    private const val XA_CARDINAL = 6L
    private const val XA_WINDOW = 33L
    private const val ANY_PROPERTY = 0L
    private const val PROP_MODE_REPLACE = 0
    private const val FORMAT_32 = 32
    private const val XRES_CLIENT_ID_PID_MASK = 2
    private const val NET_WM_STATE_REMOVE = 0L
    private const val NET_WM_STATE_SOURCE_APP = 1L
    private const val MWM_HINTS_DECORATIONS = 2L
    private const val SUBSTRUCTURE_MASKS = 1572864L // Redirect | Notify
    private const val XEVENT_BYTES = 192
    private const val CW_OVERRIDE_REDIRECT = 512L
    private const val MIN_VIDEO_PX = 80

    private val handlerInstalled = AtomicBoolean(false)
    private val swallowXErrors = object : X11ErrorHandler {
        override fun handle(display: Pointer?, error: Pointer?): Int = 0
    }

    fun snapWindowAsync(
        scope: CoroutineScope,
        process: Process?,
        pid: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ) {
        if (System.getenv("DISPLAY").isNullOrBlank()) {
            PlaybackDebugLog.note("split-x11: DISPLAY is unset; Game Day windows were not placed")
            return
        }
        if (Native.LONG_SIZE != 8) {
            PlaybackDebugLog.note("split-x11: unsupported pointer size ${Native.LONG_SIZE}")
            return
        }
        val target = WindowPositioner.coverFullMonitorHeight(
            ScreenBounds(x, y, width, height),
            WindowPositioner.awtMonitorBounds()
        )
        scope.launch(Dispatchers.IO) {
            var loggedPlace = false
            var loggedMiss = false
            var misses = 0
            for (i in 0 until 80) {
                if (!isActive) return@launch
                if (process != null && !process.isAlive) return@launch
                when (val result = runCatching { placePid(pid, target) }.getOrElse { PlaceResult.Failed(it.message) }) {
                    is PlaceResult.Unavailable -> {
                        PlaybackDebugLog.note("split-x11: ${result.detail}")
                        return@launch
                    }
                    is PlaceResult.Placed -> {
                        if (!loggedPlace) {
                            PlaybackDebugLog.note(
                                "split-x11: placed pid=$pid via ${result.pidSource} " +
                                    "window=0x${result.window.toString(16)} " +
                                    "at ${target.x},${target.y} ${target.width}x${target.height}"
                            )
                            loggedPlace = true
                        }
                    }
                    is PlaceResult.NotYet -> {
                        misses++
                        if (!loggedMiss && misses == 8) {
                            PlaybackDebugLog.note("split-x11: no video window yet for pid=$pid (${result.detail})")
                            loggedMiss = true
                        }
                    }
                    is PlaceResult.Failed -> {
                        if (!loggedMiss) {
                            PlaybackDebugLog.note("split-x11: place failed for pid=$pid: ${result.detail}")
                            loggedMiss = true
                        }
                    }
                }
                delay(150)
            }
            while (isActive) {
                if (process != null && !process.isAlive) break
                delay(1000)
                runCatching { placePid(pid, target) }
            }
        }
    }

    /**
     * Creates an override-redirect window, checks pid mapping, moves it, and
     * destroys it. Used by tests. Does not touch any other window.
     */
    internal fun placeSyntheticWindow(x: Int, y: Int, width: Int, height: Int): SyntheticPlacement {
        if (System.getenv("DISPLAY").isNullOrBlank()) {
            return SyntheticPlacement(false, "DISPLAY is unset")
        }
        val x11 = x11OrNull() ?: return SyntheticPlacement(false, "libX11 unavailable")
        val dpy = x11.XOpenDisplay(null) ?: return SyntheticPlacement(false, "XOpenDisplay failed")
        ensureErrorHandler(x11)
        var window = 0L
        try {
            val root = x11.XDefaultRootWindow(dpy).toLong()
            window = x11.XCreateSimpleWindow(
                dpy,
                NativeLong(root),
                20,
                20,
                200,
                120,
                0,
                NativeLong(0),
                NativeLong(0)
            ).toLong()
            if (window == 0L) return SyntheticPlacement(false, "XCreateSimpleWindow failed")
            markOverrideRedirect(x11, dpy, window)
            x11.XMapWindow(dpy, NativeLong(window))
            x11.XSync(dpy, 0)
            val pid = ProcessHandle.current().pid()
            val source = pidSource(x11, dpy, window, pid)
            val moved = moveWindow(x11, dpy, root, window, ScreenBounds(x, y, width, height))
            if (!moved) return SyntheticPlacement(false, "XMoveResizeWindow failed", pidSource = source)
            x11.XSync(dpy, 0)
            val origin = rootOrigin(x11, dpy, window, root)
            val size = geometry(x11, dpy, window)
            return SyntheticPlacement(
                available = true,
                detail = "ok",
                pidSource = source,
                x = origin?.first ?: -1,
                y = origin?.second ?: -1,
                width = size?.first ?: -1,
                height = size?.second ?: -1
            )
        } catch (t: Throwable) {
            return SyntheticPlacement(false, t.message ?: t.javaClass.simpleName)
        } finally {
            if (window != 0L) {
                runCatching { x11.XDestroyWindow(dpy, NativeLong(window)) }
            }
            runCatching { x11.XCloseDisplay(dpy) }
        }
    }

    internal fun xresSpecSize(): Int = XResClientIdSpec().size()

    internal fun xresSpecFieldOffset(name: String): Int = XResClientIdSpec().offsetOf(name)

    private fun placePid(pid: Long, target: ScreenBounds): PlaceResult {
        val x11 = x11OrNull() ?: return PlaceResult.Unavailable("libX11 unavailable")
        val dpy = x11.XOpenDisplay(null) ?: return PlaceResult.Unavailable("XOpenDisplay failed")
        ensureErrorHandler(x11)
        try {
            val root = x11.XDefaultRootWindow(dpy).toLong()
            if (root == 0L) return PlaceResult.Unavailable("no root window")
            val windows = topLevelWindows(x11, dpy, root)
            if (windows.isEmpty()) return PlaceResult.NotYet("no top-level windows")
            var sawXres = false
            val mine = mutableListOf<Long>()
            for (window in windows) {
                val matched = pidSource(x11, dpy, window, pid)
                if (matched == "xres") sawXres = true
                if (matched != "none") mine += window
            }
            if (mine.isEmpty()) {
                val how = if (xresOrNull() == null && !sawXres) "xres-missing and no _NET_WM_PID" else "pid not on any window"
                return PlaceResult.NotYet(how)
            }
            val video = chooseVideo(x11, dpy, mine) ?: return PlaceResult.NotYet("no video-sized window")
            val source = pidSource(x11, dpy, video, pid)
            if (!moveWindow(x11, dpy, root, video, target)) {
                return PlaceResult.Failed("XMoveResizeWindow")
            }
            x11.XFlush(dpy)
            return PlaceResult.Placed(video, source)
        } finally {
            runCatching { x11.XCloseDisplay(dpy) }
        }
    }

    private fun moveWindow(x11: X11Lib, dpy: Pointer, root: Long, window: Long, target: ScreenBounds): Boolean {
        clearMotifDecorations(x11, dpy, window)
        removeNetWmState(x11, dpy, root, window, "_NET_WM_STATE_MAXIMIZED_VERT", "_NET_WM_STATE_MAXIMIZED_HORZ")
        removeNetWmState(x11, dpy, root, window, "_NET_WM_STATE_FULLSCREEN", null)
        val moved = x11.XMoveResizeWindow(
            dpy,
            NativeLong(window),
            target.x,
            target.y,
            target.width,
            target.height
        )
        return moved != 0
    }

    private fun pidSource(x11: X11Lib, dpy: Pointer, window: Long, want: Long): String {
        val fromXres = xresPid(dpy, window)
        if (fromXres != null && fromXres == want) return "xres"
        val fromProp = netWmPid(x11, dpy, window)
        if (fromProp != null && fromProp == want) return "net_wm_pid"
        return "none"
    }

    private fun xresPid(dpy: Pointer, window: Long): Long? {
        val xres = xresOrNull() ?: return null
        val spec = XResClientIdSpec()
        spec.client = NativeLong(window)
        spec.mask = XRES_CLIENT_ID_PID_MASK
        spec.write()
        val numIds = NativeLongByReference()
        val ids = PointerByReference()
        val status = xres.XResQueryClientIds(dpy, NativeLong(1), spec, numIds, ids)
        if (status != 0) return null
        val ptr = ids.value ?: return null
        val count = numIds.value.toLong()
        try {
            if (count <= 0) return null
            val pid = xres.XResGetClientPid(ptr)
            return pid.takeIf { it > 0 }?.toLong()
        } finally {
            runCatching { xres.XResClientIdsDestroy(NativeLong(count), ptr) }
        }
    }

    private fun netWmPid(x11: X11Lib, dpy: Pointer, window: Long): Long? {
        val atom = intern(x11, dpy, "_NET_WM_PID") ?: return null
        val values = readProperty(x11, dpy, window, atom, XA_CARDINAL, 4)
        return values.firstOrNull()?.takeIf { it > 0 }
    }

    private fun topLevelWindows(x11: X11Lib, dpy: Pointer, root: Long): List<Long> {
        val ids = LinkedHashSet<Long>()
        val clientList = intern(x11, dpy, "_NET_CLIENT_LIST")
        if (clientList != null) {
            ids += readProperty(x11, dpy, root, clientList, XA_WINDOW, 4096)
        }
        val rootReturn = NativeLongByReference()
        val parentReturn = NativeLongByReference()
        val children = PointerByReference()
        val count = IntByReference()
        val ok = x11.XQueryTree(dpy, NativeLong(root), rootReturn, parentReturn, children, count)
        if (ok != 0) {
            val ptr = children.value
            val n = count.value.coerceIn(0, 4096)
            if (ptr != null && n > 0) {
                val longSize = Native.LONG_SIZE
                for (i in 0 until n) {
                    val id = ptr.getNativeLong(i.toLong() * longSize).toLong()
                    if (id != 0L) ids += id
                }
            }
            if (ptr != null) runCatching { x11.XFree(ptr) }
        }
        return ids.toList()
    }

    private fun chooseVideo(x11: X11Lib, dpy: Pointer, windows: List<Long>): Long? {
        var best: Long? = null
        var bestArea = 0L
        for (window in windows) {
            val size = geometry(x11, dpy, window) ?: continue
            if (size.first < MIN_VIDEO_PX || size.second < MIN_VIDEO_PX) continue
            val area = size.first.toLong() * size.second.toLong()
            if (area >= bestArea) {
                best = window
                bestArea = area
            }
        }
        return best ?: windows.firstOrNull()
    }

    private fun removeNetWmState(x11: X11Lib, dpy: Pointer, root: Long, window: Long, first: String, second: String?) {
        val state = intern(x11, dpy, "_NET_WM_STATE") ?: return
        val a = intern(x11, dpy, first) ?: return
        val b = second?.let { intern(x11, dpy, it) } ?: 0L
        val event = Memory(XEVENT_BYTES.toLong())
        event.clear()
        event.setInt(0, CLIENT_MESSAGE)
        event.setPointer(24, dpy)
        event.setLong(32, window)
        event.setLong(40, state)
        event.setInt(48, FORMAT_32)
        event.setLong(56, NET_WM_STATE_REMOVE)
        event.setLong(64, a)
        event.setLong(72, b)
        event.setLong(80, NET_WM_STATE_SOURCE_APP)
        x11.XSendEvent(dpy, NativeLong(root), 0, NativeLong(SUBSTRUCTURE_MASKS), event)
    }

    private fun clearMotifDecorations(x11: X11Lib, dpy: Pointer, window: Long) {
        val motif = intern(x11, dpy, "_MOTIF_WM_HINTS") ?: return
        val hints = Memory(5L * Native.LONG_SIZE)
        hints.clear()
        hints.setLong(0, MWM_HINTS_DECORATIONS)
        hints.setLong(Native.LONG_SIZE.toLong(), 0)
        hints.setLong(2L * Native.LONG_SIZE, 0)
        hints.setLong(3L * Native.LONG_SIZE, 0)
        hints.setLong(4L * Native.LONG_SIZE, 0)
        x11.XChangeProperty(
            dpy,
            NativeLong(window),
            NativeLong(motif),
            NativeLong(motif),
            FORMAT_32,
            PROP_MODE_REPLACE,
            hints,
            5
        )
    }

    private fun readProperty(
        x11: X11Lib,
        dpy: Pointer,
        window: Long,
        property: Long,
        type: Long,
        length: Long
    ): List<Long> {
        val actualType = NativeLongByReference()
        val actualFormat = IntByReference()
        val nitems = NativeLongByReference()
        val bytesAfter = NativeLongByReference()
        val prop = PointerByReference()
        val status = x11.XGetWindowProperty(
            dpy,
            NativeLong(window),
            NativeLong(property),
            NativeLong(0),
            NativeLong(length),
            0,
            NativeLong(type),
            actualType,
            actualFormat,
            nitems,
            bytesAfter,
            prop
        )
        val ptr = prop.value
        val count = nitems.value.toLong()
        if (type != ANY_PROPERTY && (status != 0 || ptr == null || count <= 0L)) {
            if (ptr != null) runCatching { x11.XFree(ptr) }
            return readProperty(x11, dpy, window, property, ANY_PROPERTY, length)
        }
        if (status != 0 || ptr == null) {
            if (ptr != null) runCatching { x11.XFree(ptr) }
            return emptyList()
        }
        return try {
            val count = nitems.value.toInt().coerceIn(0, 4096)
            if (count <= 0 || actualFormat.value != FORMAT_32) emptyList()
            else {
                val step = Native.LONG_SIZE
                List(count) { i -> ptr.getNativeLong(i.toLong() * step).toLong() }
            }
        } finally {
            runCatching { x11.XFree(ptr) }
        }
    }

    private fun geometry(x11: X11Lib, dpy: Pointer, window: Long): Pair<Int, Int>? {
        val root = NativeLongByReference()
        val x = IntByReference()
        val y = IntByReference()
        val width = IntByReference()
        val height = IntByReference()
        val border = IntByReference()
        val depth = IntByReference()
        val ok = x11.XGetGeometry(
            dpy,
            NativeLong(window),
            root,
            x,
            y,
            width,
            height,
            border,
            depth
        )
        if (ok == 0 || width.value <= 0 || height.value <= 0) return null
        return width.value to height.value
    }

    private fun rootOrigin(x11: X11Lib, dpy: Pointer, window: Long, root: Long): Pair<Int, Int>? {
        val destX = IntByReference()
        val destY = IntByReference()
        val child = NativeLongByReference()
        val ok = x11.XTranslateCoordinates(
            dpy,
            NativeLong(window),
            NativeLong(root),
            0,
            0,
            destX,
            destY,
            child
        )
        if (ok == 0) return null
        return destX.value to destY.value
    }

    private fun markOverrideRedirect(x11: X11Lib, dpy: Pointer, window: Long) {
        val attrs = Memory(112)
        attrs.clear()
        attrs.setInt(88, 1)
        x11.XChangeWindowAttributes(dpy, NativeLong(window), NativeLong(CW_OVERRIDE_REDIRECT), attrs)
    }

    private fun intern(x11: X11Lib, dpy: Pointer, name: String): Long? {
        val atom = x11.XInternAtom(dpy, name, 0).toLong()
        return atom.takeIf { it != 0L }
    }

    private fun ensureErrorHandler(x11: X11Lib) {
        if (handlerInstalled.compareAndSet(false, true)) {
            runCatching { x11.XSetErrorHandler(swallowXErrors) }
        }
    }

    private fun x11OrNull(): X11Lib? {
        x11Lib?.let { return it }
        if (loggedX11Miss.compareAndSet(false, true)) {
            PlaybackDebugLog.note("split-x11: libX11.so.6 not loaded")
        }
        return null
    }

    private fun xresOrNull(): XResLib? {
        xresLib?.let { return it }
        if (loggedXresMiss.compareAndSet(false, true)) {
            PlaybackDebugLog.note("split-x11: libXRes.so.1 not loaded; using _NET_WM_PID")
        }
        return null
    }

    private val loggedX11Miss = AtomicBoolean(false)
    private val loggedXresMiss = AtomicBoolean(false)

    private val x11Lib: X11Lib? by lazy { load("libX11.so.6", X11Lib::class.java) }
    private val xresLib: XResLib? by lazy { load("libXRes.so.1", XResLib::class.java) }

    private fun <T : Library> load(soname: String, iface: Class<T>): T? {
        val path = findLibrary(soname) ?: return null
        return try {
            Native.load(path, iface)
        } catch (t: Throwable) {
            PlaybackDebugLog.note("split-x11: Native.load $path failed: ${t.message}")
            null
        }
    }

    internal fun findLibrary(soname: String): String? {
        val dirs = listOf(
            "/usr/lib/x86_64-linux-gnu",
            "/usr/lib/aarch64-linux-gnu",
            "/usr/lib64",
            "/usr/lib",
            "/lib/x86_64-linux-gnu",
            "/lib64",
            "/lib"
        )
        dirs.map { File(it, soname) }.firstOrNull { it.isFile }?.absolutePath?.let { return it }
        return runCatching {
            val proc = ProcessBuilder("ldconfig", "-p")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            text.lineSequence()
                .firstOrNull { soname in it }
                ?.substringAfter("=>", "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && File(it).isFile }
        }.getOrNull()
    }

    internal data class SyntheticPlacement(
        val available: Boolean,
        val detail: String,
        val pidSource: String = "",
        val x: Int = 0,
        val y: Int = 0,
        val width: Int = 0,
        val height: Int = 0
    )

    private sealed class PlaceResult {
        data class Placed(val window: Long, val pidSource: String) : PlaceResult()
        data class NotYet(val detail: String) : PlaceResult()
        data class Unavailable(val detail: String) : PlaceResult()
        data class Failed(val detail: String?) : PlaceResult()
    }
}

@Structure.FieldOrder("client", "mask")
internal class XResClientIdSpec : Structure() {
    @JvmField var client: NativeLong = NativeLong(0)
    @JvmField var mask: Int = 0

    fun offsetOf(name: String): Int = fieldOffset(name)
}

internal interface X11ErrorHandler : Callback {
    fun handle(display: Pointer?, error: Pointer?): Int
}

internal interface X11Lib : Library {
    fun XOpenDisplay(name: String?): Pointer?
    fun XCloseDisplay(display: Pointer): Int
    fun XDefaultRootWindow(display: Pointer): NativeLong
    fun XInternAtom(display: Pointer, name: String, onlyIfExists: Int): NativeLong
    fun XGetWindowProperty(
        display: Pointer,
        window: NativeLong,
        property: NativeLong,
        longOffset: NativeLong,
        longLength: NativeLong,
        delete: Int,
        reqType: NativeLong,
        actualType: NativeLongByReference,
        actualFormat: IntByReference,
        nitems: NativeLongByReference,
        bytesAfter: NativeLongByReference,
        prop: PointerByReference
    ): Int

    fun XFree(data: Pointer): Int
    fun XMoveResizeWindow(display: Pointer, window: NativeLong, x: Int, y: Int, width: Int, height: Int): Int
    fun XFlush(display: Pointer): Int
    fun XSync(display: Pointer, discard: Int): Int
    fun XSendEvent(
        display: Pointer,
        window: NativeLong,
        propagate: Int,
        eventMask: NativeLong,
        event: Pointer
    ): Int

    fun XChangeProperty(
        display: Pointer,
        window: NativeLong,
        property: NativeLong,
        type: NativeLong,
        format: Int,
        mode: Int,
        data: Pointer,
        nelements: Int
    ): Int

    fun XQueryTree(
        display: Pointer,
        window: NativeLong,
        root: NativeLongByReference,
        parent: NativeLongByReference,
        children: PointerByReference,
        nchildren: IntByReference
    ): Int

    fun XGetGeometry(
        display: Pointer,
        drawable: NativeLong,
        root: NativeLongByReference,
        x: IntByReference,
        y: IntByReference,
        width: IntByReference,
        height: IntByReference,
        border: IntByReference,
        depth: IntByReference
    ): Int

    fun XTranslateCoordinates(
        display: Pointer,
        src: NativeLong,
        dest: NativeLong,
        srcX: Int,
        srcY: Int,
        destX: IntByReference,
        destY: IntByReference,
        child: NativeLongByReference
    ): Int

    fun XSetErrorHandler(handler: X11ErrorHandler): Pointer?
    fun XCreateSimpleWindow(
        display: Pointer,
        parent: NativeLong,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        borderWidth: Int,
        border: NativeLong,
        background: NativeLong
    ): NativeLong

    fun XMapWindow(display: Pointer, window: NativeLong): Int
    fun XDestroyWindow(display: Pointer, window: NativeLong): Int
    fun XChangeWindowAttributes(
        display: Pointer,
        window: NativeLong,
        valuemask: NativeLong,
        attributes: Pointer
    ): Int
}

internal interface XResLib : Library {
    fun XResQueryClientIds(
        display: Pointer,
        numSpecs: NativeLong,
        specs: XResClientIdSpec,
        numIds: NativeLongByReference,
        clientIds: PointerByReference
    ): Int

    fun XResGetClientPid(value: Pointer): Int
    fun XResClientIdsDestroy(numIds: NativeLong, clientIds: Pointer)
}
