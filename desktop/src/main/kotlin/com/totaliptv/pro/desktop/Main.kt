package com.totaliptv.pro.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.totaliptv.pro.desktop.input.SeriesNextHotkeys
import com.totaliptv.pro.desktop.player.StreamPlayer
import com.totaliptv.pro.desktop.ui.AppRoot
import java.awt.Dimension

fun main() = application {
    val state = rememberWindowState(size = DpSize(1280.dp, 800.dp))
    val appIcon = runCatching {
        BitmapPainter(useResource("icon.png") { loadImageBitmap(it) })
    }.getOrNull()
    Window(
        onCloseRequest = {
            StreamPlayer.stop()
            exitApplication()
        },
        title = "Total IPTV Pro",
        state = state,
        icon = appIcon,
        onPreviewKeyEvent = { event ->
            if (SeriesNextHotkeys.isLocalNextKey(event)) {
                SeriesNextHotkeys.requestNext()
                true
            } else {
                false
            }
        }
    ) {
        window.minimumSize = Dimension(960, 600)
        AppRoot()
    }
}
