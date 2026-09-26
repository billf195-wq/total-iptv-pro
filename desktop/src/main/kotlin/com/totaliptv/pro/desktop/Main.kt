package com.totaliptv.pro.desktop

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
import com.totaliptv.pro.desktop.player.WindowPositioner
import com.totaliptv.pro.desktop.util.AppPaths
import com.totaliptv.pro.desktop.ui.AppRoot
import com.totaliptv.pro.desktop.ui.SeriesNextHost
import com.totaliptv.pro.desktop.ui.SplashBranding
import com.totaliptv.pro.desktop.ui.TextInputFocus
import java.awt.Dimension

fun main() = application(exitProcessOnExit = true) {
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
        placement = if (initialPrefs.windowMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = initialPosition,
        size = DpSize(initialWidth, initialHeight)
    )
    val appIcon = runCatching {
        BitmapPainter(useResource("icon.png") { loadImageBitmap(it) })
    }.getOrNull()

    fun quit() {
        windowsOpen = false
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

    // Only the main frame is a Compose application Window. Series Next is an
    // AWT ComposeWindow owned by [SeriesNextHost] and disposed on quit — a
    // second `Window {}` here would keep the JVM alive after this frame closes.
    if (windowsOpen && !AppShutdown.isExiting()) {
        Window(
            onCloseRequest = {
                runCatching {
                    val cur = PreferencesStore.load()
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
                quit()
            },
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
                        state.placement = if (state.placement == WindowPlacement.Fullscreen) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Fullscreen
                        }
                        true
                    }
                    keyDown && event.key == Key.Escape &&
                        !AppPaths.isWindows && StreamPlayer.isSplitActive() -> {
                        StreamPlayer.stop()
                        if (state.placement == WindowPlacement.Fullscreen) {
                            state.placement = WindowPlacement.Floating
                        }
                        true
                    }
                    keyDown && event.key == Key.Escape && state.placement == WindowPlacement.Fullscreen -> {
                        state.placement = WindowPlacement.Floating
                        true
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
            WindowPositioner.attachAppWindow(window)
            AppRoot(seriesNextHost, onQuit = { quit() })
        }
    }
}
