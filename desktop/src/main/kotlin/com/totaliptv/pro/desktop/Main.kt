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
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.totaliptv.pro.desktop.input.SeriesNextHotkeys
import com.totaliptv.pro.desktop.input.WindowsTopMost
import com.totaliptv.pro.desktop.player.StreamPlayer
import com.totaliptv.pro.desktop.ui.AppRoot
import com.totaliptv.pro.desktop.ui.SeriesNextHost
import com.totaliptv.pro.desktop.ui.SplashBranding
import java.awt.Dimension

fun main() = application(exitProcessOnExit = true) {
    val seriesNextHost = remember { SeriesNextHost() }
    var windowsOpen by remember { mutableStateOf(true) }
    val state = rememberWindowState(size = DpSize(1280.dp, 800.dp))
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
            onCloseRequest = { quit() },
            title = SplashBranding.windowTitle(AppVersion.VERSION_NAME),
            state = state,
            icon = appIcon,
            onPreviewKeyEvent = { event ->
                val quitKey = AppShutdown.isQuitCombo(
                    keyDown = event.type == KeyEventType.KeyDown,
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
                    else -> false
                }
            }
        ) {
            window.minimumSize = Dimension(960, 600)
            AppRoot(seriesNextHost, onQuit = { quit() })
        }
    }
}
