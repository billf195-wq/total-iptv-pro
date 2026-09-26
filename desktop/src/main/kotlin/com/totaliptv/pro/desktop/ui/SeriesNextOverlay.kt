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
import com.totaliptv.pro.desktop.data.LastEpisodeBanner
import com.totaliptv.pro.desktop.data.SeriesEpisode
import com.totaliptv.pro.desktop.data.SeriesPlayback
import com.totaliptv.pro.desktop.input.SeriesNextHotkeys
import com.totaliptv.pro.desktop.input.WindowsTopMost
import com.totaliptv.pro.desktop.player.PlaybackDebugLog
import com.totaliptv.pro.desktop.player.StreamPlayer
import com.totaliptv.pro.desktop.player.WindowPositioner
import com.totaliptv.pro.desktop.util.AppPaths
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.math.abs

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
    private var shownKey: String? = null
    private var shownAtMs: Long? = null
    private var mouseMovedAtMs: Long? = null
    private var lastPointer: Point? = null
    private var userDismissed: Boolean = false
    private var holdHidden: Boolean = false
    private var bannerShown: Boolean = false
    private var shownReason: String? = null
    private var lastReveal: LastEpisodeBanner.Reveal = LastEpisodeBanner.Reveal.HIDDEN
    private var lastMode: LastEpisodeBanner.Mode = LastEpisodeBanner.Mode.HIDDEN
    private var clock: javax.swing.Timer? = null
    private var recordingThisItem: Boolean = false

    @Volatile
    private var overlayWindow: ComposeWindow? = null
    private var raisePump: AutoCloseable? = null
    private var contentAttached = false

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
        if (session == null) {
            clearSession("session-cleared")
            return
        }
        this.session = session
        val key = overlayKey(session)
        val mode = overlayMode(session)
        lastMode = mode
        if (mode == LastEpisodeBanner.Mode.HIDDEN) {
            hideIfUp("hidden")
            return
        }
        if (shownKey != key) {
            shownKey = key
            shownAtMs = nowMs
            mouseMovedAtMs = null
            lastPointer = null
            userDismissed = false
            holdHidden = false
            bannerShown = false
            shownReason = null
            lastReveal = LastEpisodeBanner.Reveal.HIDDEN
        }
        ensureClock()
        refresh(nowMs)
    }

    /** VLC left. Hide immediately; AppRoot clears the session on every non-advance. */
    fun onPlayerExited() {
        holdHidden = true
        conceal("player-exited")
    }

    /** Hide the current banner without stopping playback. Pointer motion may show it again. */
    fun dismiss(reason: String) {
        if (session == null && overlayWindow == null && !bannerShown) return
        userDismissed = true
        mouseMovedAtMs = null
        lastPointer = currentPointer()
        conceal(reason)
    }

    fun dismissLastIfMatching(key: String) = dismissIfMatching(key, "timeout")

    fun dismissIfMatching(key: String, reason: String) {
        if (session?.let { overlayKey(it) } == key) dismiss(reason)
    }

    fun isBannerShowing(): Boolean = overlayWindow != null || bannerShown

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

    private fun clearSession(reason: String) {
        val episodeId = session?.current?.id
        val wasUp = bannerShown || overlayWindow != null
        session = null
        shownKey = null
        shownAtMs = null
        mouseMovedAtMs = null
        lastPointer = null
        userDismissed = false
        holdHidden = false
        bannerShown = false
        shownReason = null
        lastReveal = LastEpisodeBanner.Reveal.HIDDEN
        stopClock()
        if (wasUp) note("hide", reason, episodeId, null, null)
        disposeWindow()
    }

    /**
     * Host-owned tick. Disposing the overlay window does not stop this timer,
     * so the intro, the last minute, and pointer motion can still show or hide it.
     */
    private fun ensureClock() {
        if (clock != null) return
        val timer = javax.swing.Timer(250) { refresh(System.currentTimeMillis()) }
        timer.isRepeats = true
        timer.start()
        clock = timer
    }

    private fun stopClock() {
        clock?.stop()
        clock = null
    }

    private fun refresh(nowMs: Long) {
        val play = session ?: return
        if (holdHidden || AppShutdown.isExiting()) {
            conceal("player-exited")
            return
        }
        sampleMouse(nowMs)
        val mode = overlayMode(play)
        lastMode = mode
        val started = shownAtMs ?: nowMs
        val decision = LastEpisodeBanner.reveal(
            mode = mode,
            startedAtMs = started,
            nowMs = nowMs,
            mouseMovedAtMs = mouseMovedAtMs,
            positionMs = StreamPlayer.lastPositionMs.takeIf { it > 0L },
            lengthMs = StreamPlayer.lastLengthMs.takeIf { it > 0L },
            userDismissed = userDismissed
        )
        if (decision == LastEpisodeBanner.Reveal.HIDDEN) {
            val reason = if (lastReveal == LastEpisodeBanner.Reveal.MOUSE) "mouse-idle" else "timeout"
            lastReveal = LastEpisodeBanner.Reveal.HIDDEN
            conceal(reason)
        } else {
            lastReveal = decision
            show(mode, decision.name.lowercase())
        }
    }

    private fun sampleMouse(nowMs: Long) {
        val point = currentPointer() ?: return
        val previous = lastPointer
        lastPointer = point
        if (previous == null) return
        if (abs(point.x - previous.x) < 6 && abs(point.y - previous.y) < 6) return
        mouseMovedAtMs = nowMs
        userDismissed = false
    }

    private fun currentPointer(): Point? {
        return try {
            MouseInfo.getPointerInfo()?.location
        } catch (_: Throwable) {
            null
        }
    }

    private fun hideIfUp(reason: String) = conceal(reason)

    /** Drops the window without cancelling the host clock and without locking the episode out. */
    private fun conceal(reason: String) {
        if (!bannerShown && overlayWindow == null) return
        val episodeId = session?.current?.id
        bannerShown = false
        shownReason = null
        note("hide", reason, episodeId, null, null)
        disposeWindow()
    }

    private fun show(mode: LastEpisodeBanner.Mode, reason: String) {
        val play = session ?: return
        val first = !bannerShown || shownReason != reason
        bannerShown = true
        shownReason = reason
        lastMode = mode
        if (overlayWindow == null) {
            val monitor = WindowPositioner.playbackMonitor()
            ensureWindow()
            if (first) note("show", reason, play.current.id, monitor.x, monitor.y)
        } else if (first) {
            val monitor = WindowPositioner.playbackMonitor()
            note("show", reason, play.current.id, monitor.x, monitor.y)
        }
    }

    private fun note(action: String, reason: String, episodeId: String?, monitorX: Int?, monitorY: Int?) {
        PlaybackDebugLog.note(
            LastEpisodeBanner.logLine(action, lastMode, reason, episodeId, monitorX, monitorY)
        )
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

    fun disposeOverlay() {
        onNext = {}
        onStop = {}
        onRecord = {}
        clearSession("disposed")
        recordingThisItem = false
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
                                mode = overlayMode(current),
                                onNext = { onNext() },
                                onDismiss = { dismiss("dismiss") },
                                onStop = { onStop() },
                                onRecord = { onRecord() },
                                recordingThisItem = recordingThisItem
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
            runCatching { w.isAlwaysOnTop = false }
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
    mode: LastEpisodeBanner.Mode = LastEpisodeBanner.overlayMode(
        session.episodes,
        session.current.season,
        session.current.episodeNum,
        session.current.id,
        session.current.streamUrl
    ),
    onNext: () -> Unit,
    onDismiss: () -> Unit = {},
    onStop: () -> Unit,
    onRecord: () -> Unit = {},
    recordingThisItem: Boolean = false
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
            if (mode == LastEpisodeBanner.Mode.LAST_BRIEF) "LAST EPISODE" else "NEXT EPISODE",
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
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        SeriesPlayback.LAST_EPISODE_MESSAGE,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
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
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Recording…", color = TipOnRecordActive, fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(
                    onClick = onRecord,
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Record", color = TipOnBg)
                }
            }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.height(48.dp)
            ) {
                Text("Hide", color = TipOnBg)
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

internal fun overlayOrigin(
    monitorX: Int,
    monitorY: Int,
    monitorWidth: Int,
    monitorHeight: Int,
    overlayWidth: Int,
    overlayHeight: Int,
    marginPx: Int
): Pair<Int, Int> {
    val x = monitorX + (monitorWidth - overlayWidth) / 2
    val y = monitorY + monitorHeight - overlayHeight - marginPx
    return x to y
}

private fun placeBottomCenter(w: ComposeWindow) {
    val monitor = WindowPositioner.playbackMonitor()
    val scale = WindowPositioner.playbackUiScale()
    val width = (420 * scale).toInt().coerceAtLeast(320)
    val height = (196 * scale).toInt().coerceAtLeast(160)
    val margin = (80 * scale).toInt().coerceAtLeast(24)
    val (x, y) = overlayOrigin(monitor.x, monitor.y, monitor.width, monitor.height, width, height, margin)
    w.setSize(width, height)
    w.setLocation(x, y)
}
