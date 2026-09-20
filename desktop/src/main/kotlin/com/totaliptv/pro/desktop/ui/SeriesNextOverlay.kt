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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.totaliptv.pro.desktop.data.SeriesEpisode
import com.totaliptv.pro.desktop.data.SeriesPlayback
import com.totaliptv.pro.desktop.input.SeriesNextHotkeys
import com.totaliptv.pro.desktop.input.WindowsTopMost
import com.totaliptv.pro.desktop.util.AppPaths
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Shared with Main so the overlay is a sibling OS window, not nested in the app frame. */
class SeriesNextHost {
    var session by mutableStateOf<ActiveSeriesPlay?>(null)
    var darkTheme by mutableStateOf(true)
    @Volatile var onNext: () -> Unit = {}
    @Volatile var onStop: () -> Unit = {}
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
fun SeriesNextOverlay(
    session: ActiveSeriesPlay,
    darkTheme: Boolean,
    onNext: () -> Unit,
    onStop: () -> Unit
) {
    val next = session.next
    val state = rememberWindowState(
        position = WindowPosition(Alignment.BottomCenter),
        size = DpSize(420.dp, 196.dp)
    )
    Window(
        onCloseRequest = onStop,
        state = state,
        title = "Next episode",
        alwaysOnTop = true,
        undecorated = true,
        resizable = false,
        onPreviewKeyEvent = { event ->
            if (SeriesNextHotkeys.isLocalNextKey(event)) {
                SeriesNextHotkeys.requestNext()
                true
            } else {
                false
            }
        }
    ) {
        LaunchedEffect(Unit) {
            window.isAlwaysOnTop = true
            runCatching { window.type = java.awt.Window.Type.UTILITY }
            while (isActive) {
                WindowsTopMost.raiseWithoutFocus(window)
                delay(400)
            }
        }
        TipTheme(darkTheme = darkTheme) {
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
                        "Linux: VLC Next also advances the remaining-episode playlist"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TipMuted
                )
            }
        }
    }
}
