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
    BackHandler { onBack() }
    val context = LocalContext.current
    val dvr = DvrActions.recorder(context)
    val snapshot by dvr.snapshot.collectAsState()
    val timeFmt = SimpleDateFormat("MMM d h:mm a", Locale.getDefault())

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        androidx.tv.material3.Button(onClick = onBack) { Text("Back") }
        Text(
            "Recordings",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 12.dp)
        )
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
                item { Text("Scheduled", style = MaterialTheme.typography.titleMedium) }
                items(snapshot.schedules, key = { it.id }) { sched ->
                    FocusableCard(
                        title = sched.title,
                        subtitle = "${DvrKind.label(sched.contentKind)} · ${sched.channelName} · ${timeFmt.format(Date(sched.startMs))} — tap to cancel",
                        onClick = { dvr.cancelSchedule(sched.id) }
                    )
                }
            }
            item { Text("Library", style = MaterialTheme.typography.titleMedium) }
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
                        subtitle = "${DvrKind.label(rec.contentKind)} · ${rec.channelName} · ${timeFmt.format(Date(rec.startMs))} · ${rec.statusEnum().name.lowercase()} — play / long-press delete",
                        onClick = { playRecording(onPlay, rec) },
                        onLongClick = {
                            dvr.deleteRecording(rec.id)
                            Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                        }
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
