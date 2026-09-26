package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.totaliptv.pro.desktop.AppShutdown
import com.totaliptv.pro.desktop.data.LastEpisodeBanner
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
    @Volatile var onRecord: () -> Unit = {}
    private var dismissedLastKey: String? = null
    private var pendingDismissKey: String? = null
    private var lastBriefKey: String? = null
    private var lastBriefShownAtMs: Long? = null
    private var dismissTimer: javax.swing.Timer? = null
    private var recordingThisItem: Boolean = false

    @Volatile
    private var overlayWindow: ComposeWindow? = null
    private var raisePump: AutoCloseable? = null
    private var contentAttached = false
    /** Laid-out banner height in px. The window follows this so the hint is not clipped. */
    private var contentHeightPx: Int = 0
    /** Screen safe height. The banner scrolls instead of extending into the taskbar. */
    private var maxBannerHeightPx by mutableStateOf(1)

    fun sync(
        session: ActiveSeriesPlay?,
        darkTheme: Boolean,
        onNext: () -> Unit,
        onStop: () -> Unit,
        onRecord: () -> Unit = {},
        recordingThisItem: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (AppShutdown.isExiting()) {
            disposeOverlay()
            return
        }
        this.onNext = onNext
        this.onStop = onStop
        this.onRecord = onRecord
        this.darkTheme = darkTheme
        this.recordingThisItem = recordingThisItem
        val key = session?.let { overlayKey(it) }
        val mode = session?.let { overlayMode(it) } ?: LastEpisodeBanner.Mode.HIDDEN
        if (session == null) {
            this.session = null
            resetLastBrief()
            disposeWindow()
            return
        }
        this.session = session
        if (mode == LastEpisodeBanner.Mode.HIDDEN) {
            resetLastBrief()
            disposeWindow()
            return
        }
        if (mode == LastEpisodeBanner.Mode.NEXT) {
            resetLastBrief()
            ensureWindow()
            return
        }
        if (lastBriefKey != key) {
            lastBriefKey = key
            lastBriefShownAtMs = nowMs
            dismissedLastKey = null
            cancelDismissTimer()
        }
        if (!LastEpisodeBanner.overlayStillVisible(mode, lastBriefShownAtMs, nowMs, dismissedLastKey, key)) {
            if (key != null) dismissedLastKey = key
            cancelDismissTimer()
            disposeWindow()
            return
        }
        ensureWindow()
        if (key != null) {
            scheduleLastEpisodeDismiss(key)
        }
    }

    fun dismissLastIfMatching(key: String) {
        if (session?.let { overlayKey(it) } == key) {
            dismissedLastKey = key
            cancelDismissTimer()
            disposeWindow()
        }
    }

    internal fun shouldKeepOverlay(
        play: ActiveSeriesPlay,
        nowMs: Long,
        firstShownAtMs: Long?,
        dismissedKey: String?
    ): Boolean = LastEpisodeBanner.overlayStillVisible(
        overlayMode(play),
        firstShownAtMs,
        nowMs,
        dismissedKey,
        overlayKey(play)
    )

    private fun resetLastBrief() {
        dismissedLastKey = null
        lastBriefKey = null
        lastBriefShownAtMs = null
        cancelDismissTimer()
    }

    fun clear() = disposeOverlay()

    internal fun overlayKey(play: ActiveSeriesPlay): String =
        "${play.seriesId}|${play.current.id}|${play.current.streamUrl}"

    internal fun overlayMode(play: ActiveSeriesPlay): LastEpisodeBanner.Mode =
        LastEpisodeBanner.overlayMode(
            play.episodes,
            play.current.season,
            play.current.episodeNum,
            play.current.id,
            play.current.streamUrl
        )

    private fun scheduleLastEpisodeDismiss(key: String) {
        if (pendingDismissKey == key) return
        cancelDismissTimer()
        pendingDismissKey = key
        val timer = javax.swing.Timer(LastEpisodeBanner.AUTO_DISMISS_MS.toInt()) {
            if (session?.let { overlayKey(it) } == key) {
                dismissedLastKey = key
                disposeWindowOnEdt()
            }
        }
        timer.isRepeats = false
        timer.start()
        dismissTimer = timer
    }

    private fun cancelDismissTimer() {
        pendingDismissKey = null
        dismissTimer?.stop()
        dismissTimer = null
    }

    fun disposeOverlay() {
        onNext = {}
        onStop = {}
        onRecord = {}
        session = null
        resetLastBrief()
        recordingThisItem = false
        contentHeightPx = 0
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
                placeOverlay(existing)
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
            overlayWindow = w
            w.background = bannerWindowColor()
            placeOverlay(w)
            if (!contentAttached) {
                w.setContent {
                    val current = session
                    if (current != null && !AppShutdown.isExiting()) {
                        TipTheme(darkTheme = darkTheme) {
                            SeriesNextOverlayBody(
                                session = current,
                                mode = overlayMode(current),
                                onNext = { onNext() },
                                onStop = { onStop() },
                                onRecord = { onRecord() },
                                recordingThisItem = recordingThisItem,
                                maxHeightPx = maxBannerHeightPx,
                                onContentHeightPx = ::onBannerHeight
                            )
                        }
                    }
                }
                contentAttached = true
            }
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
        contentHeightPx = 0
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

    private fun onBannerHeight(px: Int) {
        val window = overlayWindow
        if (window != null && !SeriesNextBannerLayout.shouldResize(window.height, px, maxBannerHeightPx)) {
            contentHeightPx = px
            return
        }
        if (px <= 0 || px == contentHeightPx) return
        contentHeightPx = px
        SwingUtilities.invokeLater {
            val current = overlayWindow ?: return@invokeLater
            current.background = bannerWindowColor()
            placeOverlay(current)
        }
    }

    private fun placeOverlay(w: ComposeWindow) {
        refreshBannerBounds(w)
        placeBottomCenter(w, contentHeightPx, maxBannerHeightPx)
    }

    private fun refreshBannerBounds(w: ComposeWindow) {
        val screen = w.graphicsConfiguration?.bounds
            ?: java.awt.Rectangle(Toolkit.getDefaultToolkit().screenSize)
        val scale = windowScale(w)
        val bottom = (80 * scale).toInt().coerceAtLeast(24)
        val top = (16 * scale).toInt().coerceAtLeast(8)
        val max = (screen.height - bottom - top).coerceAtLeast(1)
        if (maxBannerHeightPx != max) maxBannerHeightPx = max
    }

    private fun bannerWindowColor(): java.awt.Color =
        if (darkTheme) java.awt.Color(0x14, 0x1A, 0x22) else java.awt.Color(0xFF, 0xFF, 0xFF)
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
    mode: LastEpisodeBanner.Mode = LastEpisodeBanner.overlayMode(
        session.episodes,
        session.current.season,
        session.current.episodeNum,
        session.current.id,
        session.current.streamUrl
    ),
    onNext: () -> Unit,
    onStop: () -> Unit,
    onRecord: () -> Unit = {},
    recordingThisItem: Boolean = false,
    maxHeightPx: Int = 0,
    onContentHeightPx: (Int) -> Unit = {}
) {
    val next = session.next
    val buttonPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    val maxHeight = with(LocalDensity.current) {
        maxHeightPx.coerceAtLeast(1).toDp()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .reportContentHeight(onContentHeightPx)
            .wrapContentHeight()
            .background(TipSurface)
            .border(BorderStroke(3.dp, TipBlue))
            .padding(10.dp)
    ) {
        Text(
            if (mode == LastEpisodeBanner.Mode.LAST_BRIEF) "LAST EPISODE" else "NEXT EPISODE",
            color = TipBlue,
            fontWeight = FontWeight.Bold,
            fontSize = SeriesNextBannerLayout.LABEL_SP.sp
        )
        Text(
            session.seriesName.ifBlank { "Series" },
            color = TipOnBg,
            fontWeight = FontWeight.Bold,
            fontSize = SeriesNextBannerLayout.SERIES_SP.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "Now S${session.current.season}E${session.current.episodeNum}",
            color = TipMuted,
            fontSize = SeriesNextBannerLayout.META_SP.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().wrapContentHeight(),
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
                    contentPadding = buttonPadding,
                    modifier = Modifier.weight(1f).wrapContentHeight()
                ) {
                    Text(
                        "Next S${next.season}E${next.episodeNum}",
                        color = TipOnAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = SeriesNextBannerLayout.BUTTON_SP.sp
                    )
                }
            } else if (mode == LastEpisodeBanner.Mode.LAST_BRIEF) {
                Button(
                    onClick = {},
                    enabled = false,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TipSurfaceAlt,
                        contentColor = TipMuted,
                        disabledContainerColor = TipSurfaceAlt,
                        disabledContentColor = TipMuted
                    ),
                    contentPadding = buttonPadding,
                    modifier = Modifier.weight(1f).wrapContentHeight()
                ) {
                    Text(
                        SeriesPlayback.LAST_EPISODE_MESSAGE,
                        fontWeight = FontWeight.Bold,
                        fontSize = SeriesNextBannerLayout.BUTTON_SP.sp
                    )
                }
            }
            if (recordingThisItem) {
                Button(
                    onClick = onRecord,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TipRecordActive,
                        contentColor = TipOnRecordActive
                    ),
                    contentPadding = buttonPadding,
                    modifier = Modifier.wrapContentHeight()
                ) {
                    Text(
                        "Recording…",
                        color = TipOnRecordActive,
                        fontWeight = FontWeight.Bold,
                        fontSize = SeriesNextBannerLayout.BUTTON_SP.sp
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onRecord,
                    contentPadding = buttonPadding,
                    modifier = Modifier.wrapContentHeight()
                ) {
                    Text("Record", color = TipOnBg, fontSize = SeriesNextBannerLayout.BUTTON_SP.sp)
                }
            }
            OutlinedButton(
                onClick = onStop,
                contentPadding = buttonPadding,
                modifier = Modifier.wrapContentHeight()
            ) {
                Text(
                    "Stop",
                    color = TipOnBg,
                    fontSize = SeriesNextBannerLayout.BUTTON_SP.sp
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (AppPaths.isWindows) {
                "${SeriesNextHotkeys.CTRL_RIGHT_HINT} or ${SeriesNextHotkeys.MEDIA_NEXT_HINT} — not VLC’s Next"
            } else {
                "Advances at end of episode · ${SeriesNextHotkeys.CTRL_RIGHT_HINT} when this app is focused — not VLC’s Next"
            },
            color = TipMuted,
            fontSize = SeriesNextBannerLayout.HINT_SP.sp
        )
    }
}

/**
 * Reports the banner's full height, ignoring a short window. The window then
 * grows to that height (capped to the screen). The scroll modifier keeps the
 * tail reachable when the cap is smaller than the text.
 */
private fun Modifier.reportContentHeight(onHeightPx: (Int) -> Unit): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
    onHeightPx(placeable.height)
    val width = placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
    val height = placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(width, height) {
        placeable.place(0, 0)
    }
}

private fun onEdt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) {
        block()
    } else {
        runCatching { SwingUtilities.invokeAndWait(block) }
    }
}

private fun windowScale(w: ComposeWindow?): Double =
    w?.graphicsConfiguration?.defaultTransform?.scaleX?.takeIf { it > 0 } ?: 1.0

private fun placeBottomCenter(w: ComposeWindow, contentHeightPx: Int, maxHeightPx: Int) {
    val screen = w.graphicsConfiguration?.bounds
        ?: java.awt.Rectangle(Toolkit.getDefaultToolkit().screenSize)
    val scale = windowScale(w)
    val width = (420 * scale).toInt().coerceAtLeast(320)
    val cap = maxHeightPx.coerceAtLeast(1)
    val height = if (contentHeightPx > 0) {
        SeriesNextBannerLayout.targetWindowPx(contentHeightPx, cap)
    } else {
        // Placeholder until the first measure. Content then resizes the window.
        (200 * scale).toInt().coerceIn(1, cap)
    }
    w.setSize(width, height)
    val x = screen.x + (screen.width - width) / 2
    val y = screen.y + screen.height - height - (80 * scale).toInt().coerceAtLeast(24)
    w.setLocation(x, y)
}
