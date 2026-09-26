package com.totaliptv.pro.ui.dvr

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.dvr.DvrActions
import com.totaliptv.pro.dvr.DvrKind
import com.totaliptv.pro.dvr.RecordingEntry
import com.totaliptv.pro.ui.components.FocusableCard
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordingsScreen(
    onPlay: (MediaItem) -> Unit,
    onBack: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<RecordingEntry?>(null) }
    BackHandler(enabled = pendingDelete == null) { onBack() }
    BackHandler(enabled = pendingDelete != null) { pendingDelete = null }
    val context = LocalContext.current
    val dvr = DvrActions.recorder(context)
    val snapshot by dvr.snapshot.collectAsState()
    val timeFmt = SimpleDateFormat("MMM d h:mm a", Locale.getDefault())

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        androidx.tv.material3.Button(onClick = onBack) { Text("Back") }
        Text(
            "Recordings",
            style = MaterialTheme.typography.headlineMedium,
            color = OnCinema,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        pendingDelete?.let { doomed ->
            Text("Delete ${doomed.title}?", color = OnCinema)
            androidx.tv.material3.Button(onClick = {
                dvr.deleteRecording(doomed.id)
                pendingDelete = null
                Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
            }) { Text("Delete") }
            androidx.tv.material3.Button(onClick = { pendingDelete = null }) { Text("Cancel") }
        }
        Text(
            "Saved on this TV only — ${snapshot.recordingsDir}",
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )
        snapshot.active?.let { active ->
            FocusableCard(
                title = "Recording now: ${active.title}",
                subtitle = "${DvrKind.label(active.contentKind)} · ${active.channelName} — tap to stop",
                onClick = { DvrActions.stop(context) }
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (snapshot.schedules.isNotEmpty()) {
                item { Text("Scheduled", style = MaterialTheme.typography.titleMedium, color = OnCinema) }
                items(snapshot.schedules, key = { it.id }) { sched ->
                    FocusableCard(
                        title = sched.title,
                        subtitle = "${DvrKind.label(sched.contentKind)} · ${sched.channelName} · ${timeFmt.format(Date(sched.startMs))} — tap to cancel",
                        onClick = { dvr.cancelSchedule(sched.id) }
                    )
                }
            }
            item { Text("Library", style = MaterialTheme.typography.titleMedium, color = OnCinema) }
            val library = snapshot.recordings.filterNot { it.isActive() }
            if (library.isEmpty()) {
                item {
                    Text(
                        "No recordings yet. Record Live/Guide, a series episode, or a movie. Files stay on this device.",
                        color = OnCinemaMuted
                    )
                }
            } else {
                items(library, key = { it.id }) { rec ->
                    FocusableCard(
                        title = rec.title,
                        subtitle = buildString {
                            append(DvrKind.label(rec.contentKind))
                            append(" · ")
                            append(rec.channelName)
                            append(" · ")
                            append(timeFmt.format(Date(rec.startMs)))
                            append(" · ")
                            append(rec.statusEnum().name.lowercase())
                            rec.errorMessage?.takeIf { it.isNotBlank() }?.let {
                                append(" · ")
                                append(it)
                            }
                            append(" — play / long-press delete")
                        },
                        onClick = { playRecording(onPlay, rec) },
                        onLongClick = { pendingDelete = rec }
                    )
                }
            }
        }
    }
}

private fun playRecording(onPlay: (MediaItem) -> Unit, rec: RecordingEntry) {
    if (!rec.playable()) return
    onPlay(
        MediaItem(
            id = "rec-${rec.id}",
            name = rec.title,
            streamUrl = rec.filePath,
            categoryId = null,
            kind = ContentKind.VOD,
            logoUrl = null
        )
    )
}
