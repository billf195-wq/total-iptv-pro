package com.totaliptv.pro.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.totaliptv.pro.desktop.data.PreferencesStore
import com.totaliptv.pro.desktop.input.SeriesNextHotkeys
import com.totaliptv.pro.desktop.input.WindowsTopMost
import com.totaliptv.pro.desktop.player.StreamPlayer
import com.totaliptv.pro.desktop.player.UncaughtLog
import com.totaliptv.pro.desktop.player.WindowPositioner
import com.totaliptv.pro.desktop.util.AppPaths
import com.totaliptv.pro.desktop.ui.AppRoot
import com.totaliptv.pro.desktop.ui.SeriesNextHost
import com.totaliptv.pro.desktop.ui.SplashBranding
import com.totaliptv.pro.desktop.ui.TextInputFocus
import java.awt.Dimension

fun main() {
    UncaughtLog.install()
    application(exitProcessOnExit = true) {
    val seriesNextHost = remember { SeriesNextHost() }
    var windowsOpen by remember { mutableStateOf(true) }
    val initialPrefs = remember { PreferencesStore.load() }
    val initialWidth = if (initialPrefs.windowWidth >= 960) initialPrefs.windowWidth.dp else 1280.dp
    val initialHeight = if (initialPrefs.windowHeight >= 600) initialPrefs.windowHeight.dp else 800.dp
    val initialPosition = if (initialPrefs.windowX != null && initialPrefs.windowY != null) {
        WindowPosition(initialPrefs.windowX.dp, initialPrefs.windowY.dp)
    } else {
        WindowPosition.PlatformDefault
    }
    val state = rememberWindowState(
        placement = when {
            initialPrefs.startFullscreen -> WindowPlacement.Fullscreen   // 1.2.28: borderless, no title bar or taskbar
            initialPrefs.windowMaximized -> WindowPlacement.Maximized
            else -> WindowPlacement.Floating
        },
        position = initialPosition,
        size = DpSize(initialWidth, initialHeight)
    )
    val appIcon = runCatching {
        BitmapPainter(useResource("icon.png") { loadImageBitmap(it) })
    }.getOrNull()

    fun quit() {
        windowsOpen = false
        runCatching {
            val cur = PreferencesStore.load()
            // Full screen covers the whole monitor; keep the last windowed size and position instead.
            if (state.placement == WindowPlacement.Fullscreen) return@runCatching
            val absolute = state.position as? WindowPosition.Absolute
            PreferencesStore.save(
                cur.copy(
                    windowWidth = state.size.width.value.toInt().coerceAtLeast(960),
                    windowHeight = state.size.height.value.toInt().coerceAtLeast(600),
                    windowX = absolute?.x?.value?.toInt(),
                    windowY = absolute?.y?.value?.toInt(),
                    windowMaximized = state.placement == WindowPlacement.Maximized
                )
            )
        }
        AppShutdown.requestQuit(
            disposeOverlay = { seriesNextHost.disposeOverlay() },
            stopHotkeys = { SeriesNextHotkeys.shutdown() },
            stopPlayer = {
                StreamPlayer.stop()
                com.totaliptv.pro.desktop.dvr.DvrRecorder.shutdown()
            },
            stopTopMost = { WindowsTopMost.shutdown() },
            exitApplication = { exitApplication() }
        )
    }

    // Leaving full screen goes back to how the window was before (maximized or floating).
    fun windowedPlacement(): WindowPlacement =
        if (PreferencesStore.load().windowMaximized) WindowPlacement.Maximized else WindowPlacement.Floating
    val fsScope = rememberCoroutineScope()
    fun setFullscreen(on: Boolean, save: Boolean) {
        WindowFullscreen.log("setFullscreen on=$on save=$save from=${state.placement}")
        if (on) {
            state.placement = WindowPlacement.Fullscreen
        } else {
            // Windows: going straight from Fullscreen to Maximized keeps the borderless frame (no title bar).
            // Go to a normal window first so the title bar comes back, then maximize if that's how it was.
            val target = windowedPlacement()
            state.placement = WindowPlacement.Floating
            if (target == WindowPlacement.Maximized) fsScope.launch {
                delay(250)
                if (state.placement == WindowPlacement.Floating) state.placement = WindowPlacement.Maximized
            }
        }
        if (save) runCatching {
            val cur = PreferencesStore.load()
            if (cur.startFullscreen != on) PreferencesStore.save(cur.copy(startFullscreen = on))
        }
    }
    LaunchedEffect(WindowFullscreen.request) {
        val r = WindowFullscreen.request ?: return@LaunchedEffect
        WindowFullscreen.request = null
        setFullscreen(r, save = false)   // Settings already saved the choice
    }

    // Only the main frame is a Compose application Window. Series Next is an
    // AWT ComposeWindow owned by [SeriesNextHost] and disposed on quit — a
    // second `Window {}` here would keep the JVM alive after this frame closes.
    if (windowsOpen && !AppShutdown.isExiting()) {
        Window(
            onCloseRequest = { quit() },
            title = SplashBranding.windowTitle(AppVersion.VERSION_NAME),
            state = state,
            icon = appIcon,
            onPreviewKeyEvent = { event ->
                val keyDown = event.type == KeyEventType.KeyDown
                val quitKey = AppShutdown.isQuitCombo(
                    keyDown = keyDown,
                    ctrlOrMeta = event.isCtrlPressed || event.isMetaPressed,
                    isQ = event.key == Key.Q
                )
                when {
                    quitKey -> {
                        quit()
                        true
                    }
                    SeriesNextHotkeys.isLocalNextKey(event) -> {
                        SeriesNextHotkeys.requestNext()
                        true
                    }
                    keyDown && event.key == Key.F11 -> {
                        setFullscreen(WindowFullscreen.toggled(state.placement == WindowPlacement.Fullscreen), save = true)
                        true
                    }
                    keyDown && event.key == Key.Escape -> when (WindowFullscreen.escAction(
                        fullscreen = state.placement == WindowPlacement.Fullscreen,
                        bannerShowing = seriesNextHost.isBannerShowing(),
                        splitActive = StreamPlayer.isSplitActive(),
                        playing = StreamPlayer.isPlaying()
                    )) {
                        WindowFullscreen.EscAction.DISMISS_BANNER -> { seriesNextHost.dismiss("esc"); true }
                        WindowFullscreen.EscAction.STOP_SPLIT -> {
                            // unchanged from 1.2.27
                            StreamPlayer.stop()
                            if (state.placement == WindowPlacement.Fullscreen) state.placement = WindowPlacement.Floating
                            true
                        }
                        WindowFullscreen.EscAction.PASS_TO_PLAYER -> false   // a video is playing: Esc keeps its old job
                        WindowFullscreen.EscAction.LEAVE_FULLSCREEN -> { setFullscreen(false, save = false); true }
                        WindowFullscreen.EscAction.NONE -> false
                    }
                    keyDown && event.key == Key.Spacebar && !TextInputFocus.isActive() && StreamPlayer.isPlaying() -> {
                        StreamPlayer.stop()
                        true
                    }
                    else -> false
                }
            }
        ) {
            window.minimumSize = Dimension(960, 600)
            LaunchedEffect(Unit) {
                androidx.compose.runtime.snapshotFlow { state.placement }.collect {
                    delay(600)
                    WindowFullscreen.log("placement=$it awt=${window.placement} ext=${window.extendedState} bounds=${window.bounds} undecorated=${window.isUndecorated}")
                }
            }
            WindowPositioner.attachAppWindow(window)
            AppRoot(seriesNextHost, onQuit = { quit() })
        }
    }
    }
}
