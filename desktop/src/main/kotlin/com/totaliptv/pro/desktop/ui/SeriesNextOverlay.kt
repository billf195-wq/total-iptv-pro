package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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

data class ActiveSeriesPlay(
    val episodes: List<SeriesEpisode>,
    val seriesName: String,
    val seriesId: Int?,
    val current: SeriesEpisode
) {
    val next: SeriesEpisode?
        get() = SeriesPlayback.nextEpisode(episodes, current.season, current.episodeNum, current.id)
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
        position = WindowPosition(Alignment.TopEnd),
        size = DpSize(320.dp, 168.dp)
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
            while (isActive) {
                WindowsTopMost.raiseWithoutFocus(window)
                delay(1500)
            }
        }
        TipTheme(darkTheme = darkTheme) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(TipSurface, RoundedCornerShape(0.dp))
                    .padding(12.dp)
            ) {
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
                Spacer(Modifier.height(8.dp))
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
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                "Next S${next.season}E${next.episodeNum}",
                                color = TipOnAmber,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text(
                            "Last episode",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TipMuted,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedButton(onClick = onStop) {
                        Text("Stop", color = TipOnBg)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (AppPaths.isWindows) {
                        "${SeriesNextHotkeys.CTRL_RIGHT_HINT} or ${SeriesNextHotkeys.MEDIA_NEXT_HINT} — VLC Next stays on this episode"
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
