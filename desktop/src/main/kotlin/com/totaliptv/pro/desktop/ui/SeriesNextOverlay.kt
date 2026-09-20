package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.totaliptv.pro.desktop.AppShutdown
import com.totaliptv.pro.desktop.data.SeriesEpisode
import com.totaliptv.pro.desktop.data.SeriesPlayback
import com.totaliptv.pro.desktop.input.SeriesNextHotkeys
import com.totaliptv.pro.desktop.input.WindowsTopMost
import com.totaliptv.pro.desktop.util.AppPaths
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Shared with Main so Series Next can sit above fullscreen VLC.
 *
 * This is **not** a Compose Desktop `application { Window {} }` child.
 * A second application Window stays in Compose's window list after Quit,
 * keeps the JVM alive, and the always-on-top raise path re-shows the main
 * frame (restart loop). The overlay is an independently disposed AWT
 * [ComposeWindow]; Quit must [disposeOverlay] before `exitApplication`.
 */
class SeriesNextHost {
    var session by mutableStateOf<ActiveSeriesPlay?>(null)
        private set
    var darkTheme by mutableStateOf(true)
        private set
    @Volatile var onNext: () -> Unit = {}
    @Volatile var onStop: () -> Unit = {}

    @Volatile
    private var overlayWindow: ComposeWindow? = null
    private var raisePump: AutoCloseable? = null
    private var contentAttached = false

    fun sync(
        session: ActiveSeriesPlay?,
        darkTheme: Boolean,
        onNext: () -> Unit,
        onStop: () -> Unit
    ) {
        if (AppShutdown.isExiting()) {
            disposeOverlay()
            return
        }
        this.onNext = onNext
        this.onStop = onStop
        this.darkTheme = darkTheme
        this.session = session
        if (session == null) {
            disposeWindow()
        } else {
            ensureWindow()
        }
    }

    fun clear() = disposeOverlay()

    fun disposeOverlay() {
        onNext = {}
        onStop = {}
        session = null
        if (overlayWindow == null && raisePump == null) return
        disposeWindow()
    }

    fun isOverlayWindowAlive(): Boolean = overlayWindow != null

    private fun ensureWindow() {
        onEdt {
            if (AppShutdown.isExiting()) {
                disposeWindowOnEdt()
                return@onEdt
            }
            val existing = overlayWindow
            if (existing != null) {
                if (!existing.isVisible) existing.isVisible = true
                placeBottomCenter(existing)
                return@onEdt
            }
            val w = ComposeWindow().apply {
                title = OVERLAY_TITLE
                isUndecorated = true
                isAlwaysOnTop = true
                isResizable = false
                isFocusable = false
                focusableWindowState = false
                isAutoRequestFocus = false
                defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                type = java.awt.Window.Type.UTILITY
            }
            w.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent?) {
                    onStop()
                }
            })
            if (!contentAttached) {
                w.setContent {
                    val current = session
                    if (current != null && !AppShutdown.isExiting()) {
                        TipTheme(darkTheme = darkTheme) {
                            SeriesNextOverlayBody(
                                session = current,
                                onNext = { onNext() },
                                onStop = { onStop() }
                            )
                        }
                    }
                }
                contentAttached = true
            }
            overlayWindow = w
            placeBottomCenter(w)
            w.isVisible = true
            raisePump = WindowsTopMost.startRaisePump(w)
        }
    }

    private fun disposeWindow() {
        onEdt { disposeWindowOnEdt() }
    }

    private fun disposeWindowOnEdt() {
        val pump = raisePump
        raisePump = null
        runCatching { pump?.close() }
        val w = overlayWindow
        overlayWindow = null
        contentAttached = false
        if (w != null) {
            runCatching { w.isVisible = false }
            runCatching { w.dispose() }
        }
    }

    companion object {
        /**
         * False by design: overlay must not join Compose's application window
         * set. If this were a second `Window` composable, Quit would not end
         * the process (two windows keep the JVM alive).
         */
        const val IS_COMPOSE_APPLICATION_WINDOW: Boolean = false
        const val OVERLAY_TITLE: String = "Next episode"
    }
}

data class ActiveSeriesPlay(
    val episodes: List<SeriesEpisode>,
    val seriesName: String,
    val seriesId: Int?,
    val current: SeriesEpisode
) {
    val next: SeriesEpisode?
        get() = SeriesPlayback.nextAfterPlaying(
            episodes,
            current.season,
            current.episodeNum,
            current.id,
            current.streamUrl
        )
}

@Composable
fun SeriesNextOverlayBody(
    session: ActiveSeriesPlay,
    onNext: () -> Unit,
    onStop: () -> Unit
) {
    val next = session.next
    Column(
        Modifier
            .fillMaxSize()
            .background(TipSurface)
            .border(BorderStroke(3.dp, TipBlue))
            .padding(14.dp)
    ) {
        Text(
            if (next != null) "NEXT EPISODE" else "LAST EPISODE",
            color = TipBlue,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Text(
            session.seriesName.ifBlank { "Series" },
            style = MaterialTheme.typography.titleMedium,
            color = TipOnBg,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            "Now S${session.current.season}E${session.current.episodeNum}",
            style = MaterialTheme.typography.bodyMedium,
            color = TipMuted
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (next != null) {
                Button(
                    onClick = onNext,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TipBlue,
                        contentColor = TipOnAmber
                    ),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        "Next S${next.season}E${next.episodeNum}",
                        color = TipOnAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            } else {
                Button(
                    onClick = {},
                    enabled = false,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TipSurfaceAlt,
                        contentColor = TipMuted,
                        disabledContainerColor = TipSurfaceAlt,
                        disabledContentColor = TipMuted
                    ),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        SeriesPlayback.LAST_EPISODE_MESSAGE,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.height(48.dp)
            ) {
                Text("Stop", color = TipOnBg)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (AppPaths.isWindows) {
                "${SeriesNextHotkeys.CTRL_RIGHT_HINT} or ${SeriesNextHotkeys.MEDIA_NEXT_HINT} — not VLC’s Next"
            } else {
                "Advances at end of episode · ${SeriesNextHotkeys.CTRL_RIGHT_HINT} when this app is focused — not VLC’s Next"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TipMuted
        )
    }
}

private fun onEdt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) {
        block()
    } else {
        runCatching { SwingUtilities.invokeAndWait(block) }
    }
}

private fun placeBottomCenter(w: ComposeWindow) {
    val screen = w.graphicsConfiguration?.bounds
        ?: java.awt.Rectangle(Toolkit.getDefaultToolkit().screenSize)
    val scale = w.graphicsConfiguration?.defaultTransform?.scaleX?.takeIf { it > 0 } ?: 1.0
    val width = (420 * scale).toInt().coerceAtLeast(320)
    val height = (196 * scale).toInt().coerceAtLeast(160)
    w.setSize(width, height)
    val x = screen.x + (screen.width - width) / 2
    val y = screen.y + screen.height - height - (80 * scale).toInt().coerceAtLeast(24)
    w.setLocation(x, y)
}
