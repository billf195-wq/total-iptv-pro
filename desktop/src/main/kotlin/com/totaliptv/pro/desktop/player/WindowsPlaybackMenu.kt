package com.totaliptv.pro.desktop.player

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.Native
import com.totaliptv.pro.desktop.util.AppPaths
import java.awt.Color
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.BorderFactory
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Windows-only right-click menu for single-play VLC fullscreen.
 *
 * VLC's own right-click drops out of fullscreen or opens the full Qt window.
 * A low-level mouse hook swallows right-clicks that land on the VLC process
 * and this menu is shown instead. Linux never installs the hook. Game Day
 * does not arm it.
 */
object WindowsPlaybackMenu {
    internal const val WM_LBUTTONDOWN = 0x0201
    internal const val WM_RBUTTONDOWN = 0x0204
    internal const val WM_RBUTTONUP = 0x0205
    internal const val WM_RBUTTONDBLCLK = 0x0206

    enum class Action(val label: String) {
        PAUSE_TOGGLE("Pause"),
        AUDIO_TRACK("Audio track"),
        SUBTITLE_TRACK("Subtitles"),
        STOP("Stop"),
        NEXT_EPISODE("Next episode")
    }

    /** RC command for actions VLC performs. Stop and Next stay in the app. */
    internal fun rcCommand(action: Action): String? = when (action) {
        Action.PAUSE_TOGGLE -> "pause"
        Action.AUDIO_TRACK -> "key key-audio-track"
        Action.SUBTITLE_TRACK -> "key key-subtitle-track"
        Action.STOP, Action.NEXT_EPISODE -> null
    }

    internal fun pauseLabel(paused: Boolean): String = if (paused) "Play" else "Pause"

    /** Pause/Play, Audio track, Subtitles, Stop, then Next episode when a series has one. */
    internal fun menuActions(hasNextEpisode: Boolean): List<Action> {
        val actions = mutableListOf(
            Action.PAUSE_TOGGLE,
            Action.AUDIO_TRACK,
            Action.SUBTITLE_TRACK,
            Action.STOP
        )
        if (hasNextEpisode) actions += Action.NEXT_EPISODE
        return actions
    }

    internal fun unsignedPid(raw: Int): Long = raw.toLong() and 0xFFFFFFFFL

    internal fun sameProcess(targetPid: Long, windowPid: Long): Boolean {
        if (targetPid <= 0L || windowPid <= 0L) return false
        return (targetPid and 0xFFFFFFFFL) == (windowPid and 0xFFFFFFFFL)
    }

    internal fun isRightButton(message: Int): Boolean =
        message == WM_RBUTTONDOWN || message == WM_RBUTTONUP || message == WM_RBUTTONDBLCLK

    /** Swallow the click when it is a right button on the playing VLC process. */
    internal fun shouldSwallowRightClick(message: Int, targetPid: Long, windowPid: Long): Boolean =
        isRightButton(message) && sameProcess(targetPid, windowPid)

    /** Open the menu on button-up so down+up does not open it twice. */
    internal fun shouldOpenMenu(message: Int): Boolean = message == WM_RBUTTONUP

    @Volatile
    private var hasNextEpisode: () -> Boolean = { false }

    @Volatile
    private var onStop: () -> Unit = { StreamPlayer.stop() }

    @Volatile
    private var onNext: () -> Unit = {}

    fun bind(hasNextEpisode: () -> Boolean, onStop: () -> Unit, onNext: () -> Unit) {
        this.hasNextEpisode = hasNextEpisode
        this.onStop = onStop
        this.onNext = onNext
    }

    private val lock = Any()
    private val targetPid = AtomicLong(0)
    private val nativeThreadId = AtomicInteger(0)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vlc-menu-actions").apply { isDaemon = true }
    }

    @Volatile
    private var hook: WinUser.HHOOK? = null

    @Volatile
    private var sessionRunning: AtomicBoolean? = null

    /** Strong ref so the native callback is not collected. */
    @Volatile
    private var callback: WinUser.LowLevelMouseProc? = null

    @Volatile
    private var anchor: JWindow? = null

    @Volatile
    private var popup: JPopupMenu? = null

    fun arm(process: Process) {
        if (!AppPaths.isWindows) return
        val pid = process.pid()
        if (pid <= 0L || !process.isAlive) return
        synchronized(lock) {
            disarmLocked()
            targetPid.set(pid)
            startHookLocked(process)
        }
    }

    fun disarm() {
        val had = synchronized(lock) { disarmLocked() }
        if (had) {
            PlaybackDebugLog.note("vlc-menu disarm")
            runCatching { SwingUtilities.invokeLater { hideMenu() } }
        }
    }

    private fun disarmLocked(): Boolean {
        val had = targetPid.get() != 0L || hook != null || sessionRunning != null
        sessionRunning?.set(false)
        sessionRunning = null
        targetPid.set(0)
        val installed = hook
        hook = null
        val tid = nativeThreadId.getAndSet(0)
        if (AppPaths.isWindows) {
            if (installed != null) {
                runCatching { User32.INSTANCE.UnhookWindowsHookEx(installed) }
            }
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
        }
        return had
    }

    private fun startHookLocked(process: Process) {
        val running = AtomicBoolean(true)
        sessionRunning = running
        val ready = CountDownLatch(1)
        val installed = AtomicBoolean(false)
        val proc = WinUser.LowLevelMouseProc { nCode, wParam, info ->
            onMouse(nCode, wParam, info)
        }
        callback = proc
        val thread = Thread({
            nativeThreadId.set(Kernel32.INSTANCE.GetCurrentThreadId())
            val hMod = Kernel32.INSTANCE.GetModuleHandle(null)
            val installedHook = runCatching {
                User32.INSTANCE.SetWindowsHookEx(WinUser.WH_MOUSE_LL, proc, hMod, 0)
            }.getOrNull()
            hook = installedHook
            if (installedHook == null) {
                running.set(false)
                ready.countDown()
                return@Thread
            }
            installed.set(true)
            ready.countDown()
            val msg = WinUser.MSG()
            try {
                while (running.get()) {
                    val got = User32.INSTANCE.GetMessage(msg, null, 0, 0)
                    if (got == 0 || got == -1) break
                }
            } finally {
                runCatching { User32.INSTANCE.UnhookWindowsHookEx(installedHook) }
                if (hook == installedHook) hook = null
            }
        }, "vlc-right-click")
        thread.isDaemon = true
        thread.start()
        ready.await(2, TimeUnit.SECONDS)
        if (!installed.get()) {
            PlaybackDebugLog.note("vlc-menu hook failed")
            targetPid.set(0)
            return
        }
        PlaybackDebugLog.note("vlc-menu arm pid=${process.pid()}")
        val watchedPid = process.pid()
        Thread({
            runCatching { process.waitFor() }
            if (targetPid.get() == watchedPid) disarm()
        }, "vlc-menu-watch").apply { isDaemon = true; start() }
    }

    /** WPARAM's numeric value. JNA prints IntegerType as a decimal string. */
    internal fun messageCode(wParam: WinDef.WPARAM): Int =
        wParam.toString().toLongOrNull()?.toInt() ?: 0

    private fun onMouse(nCode: Int, wParam: WinDef.WPARAM, info: WinUser.MSLLHOOKSTRUCT): WinDef.LRESULT {
        if (nCode < 0) return callNext(nCode, wParam, info)
        val message = messageCode(wParam)
        val pid = targetPid.get()
        if (!isRightButton(message) || pid <= 0L) return callNext(nCode, wParam, info)
        val windowPid = pidAt(info.pt.x, info.pt.y)
        if (!shouldSwallowRightClick(message, pid, windowPid)) return callNext(nCode, wParam, info)
        if (shouldOpenMenu(message)) {
            val x = info.pt.x
            val y = info.pt.y
            worker.execute { openMenu(x, y) }
        }
        return WinDef.LRESULT(1)
    }

    private fun callNext(nCode: Int, wParam: WinDef.WPARAM, info: WinUser.MSLLHOOKSTRUCT): WinDef.LRESULT {
        val installed = hook
        val pointer = info.pointer
        if (installed == null || pointer == null) return WinDef.LRESULT(0)
        return User32.INSTANCE.CallNextHookEx(
            installed,
            nCode,
            wParam,
            WinDef.LPARAM(Pointer.nativeValue(pointer))
        )
    }

    private fun pidAt(x: Int, y: Int): Long {
        val api = pointApi ?: return -1
        val hwnd = runCatching { api.WindowFromPoint(WinDef.POINT.ByValue(x, y)) }.getOrNull() ?: return -1
        val ref = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, ref)
        return unsignedPid(ref.value)
    }

    private fun openMenu(x: Int, y: Int) {
        if (targetPid.get() == 0L) return
        val paused = runCatching { VlcControl.queryPaused(StreamPlayer.PROGRESS_RC_PORT) }.getOrDefault(false)
        val next = runCatching { hasNextEpisode() }.getOrDefault(false)
        if (targetPid.get() == 0L) return
        SwingUtilities.invokeLater {
            if (targetPid.get() == 0L) return@invokeLater
            showMenu(x, y, paused, next)
        }
    }

    private fun showMenu(x: Int, y: Int, paused: Boolean, next: Boolean) {
        hideMenu()
        val menu = JPopupMenu()
        menu.isLightWeightPopupEnabled = false
        menu.background = Color(20, 20, 20)
        menu.border = BorderFactory.createLineBorder(Color(90, 90, 90))
        for (action in menuActions(next)) {
            val label = if (action == Action.PAUSE_TOGGLE) pauseLabel(paused) else action.label
            val item = JMenuItem(label)
            item.background = Color(20, 20, 20)
            item.foreground = Color(235, 235, 235)
            item.isOpaque = true
            item.font = item.font.deriveFont(14f)
            item.addActionListener {
                hideMenu()
                worker.execute { perform(action) }
            }
            menu.add(item)
        }
        val owner = JWindow()
        owner.isAlwaysOnTop = true
        owner.focusableWindowState = true
        owner.background = Color(0, 0, 0, 0)
        owner.setSize(1, 1)
        owner.setLocation(x, y)
        owner.isVisible = true
        anchor = owner
        popup = menu
        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
                SwingUtilities.getWindowAncestor(menu)?.isAlwaysOnTop = true
            }

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                owner.isVisible = false
            }

            override fun popupMenuCanceled(e: PopupMenuEvent) {
                owner.isVisible = false
            }
        })
        menu.show(owner, 0, 0)
        raiseMenu(menu)
        javax.swing.Timer(80) { event ->
            (event.source as javax.swing.Timer).stop()
            if (popup === menu) raiseMenu(menu)
        }.apply { isRepeats = false; start() }
        PlaybackDebugLog.note("vlc-menu show x=$x y=$y next=$next paused=$paused")
    }

    private fun raiseMenu(menu: JPopupMenu) {
        SwingUtilities.getWindowAncestor(menu)?.isAlwaysOnTop = true
    }

    private fun hideMenu() {
        val menu = popup
        popup = null
        runCatching { menu?.isVisible = false }
        val owner = anchor
        anchor = null
        if (owner != null) {
            runCatching { owner.isVisible = false }
            runCatching { owner.dispose() }
        }
    }

    private fun perform(action: Action) {
        PlaybackDebugLog.note("vlc-menu ${action.name}")
        val command = rcCommand(action)
        if (command != null) {
            VlcControl.sendCommand(StreamPlayer.PROGRESS_RC_PORT, command)
            return
        }
        when (action) {
            Action.STOP -> runCatching { onStop() }
            Action.NEXT_EPISODE -> runCatching { onNext() }
            else -> Unit
        }
    }

    private val pointApi: PointApi? by lazy {
        if (!AppPaths.isWindows) return@lazy null
        try {
            Native.load("user32", PointApi::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    private interface PointApi : StdCallLibrary {
        fun WindowFromPoint(point: WinDef.POINT.ByValue): WinDef.HWND?
    }
}
