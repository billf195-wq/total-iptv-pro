package com.totaliptv.pro.ui.dvr

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.dvr.DvrActions
import com.totaliptv.pro.dvr.DvrKind
import com.totaliptv.pro.dvr.RecordingEntry
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import com.totaliptv.pro.ui.theme.tipScreenBrush
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PhoneRecordingsScreen(
    onPlay: (MediaItem) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val dvr = DvrActions.recorder(context)
    val snapshot by dvr.snapshot.collectAsState()
    val timeFmt = SimpleDateFormat("MMM d h:mm a", Locale.getDefault())

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(tipScreenBrush())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Recordings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = OnCinema)
            Spacer(Modifier.height(4.dp))
            Text("Saved on this phone only — ${snapshot.recordingsDir}", color = OnCinemaMuted, style = MaterialTheme.typography.bodySmall)
        }
        snapshot.active?.let { active ->
            item {
                Text("Recording now: ${active.title}", color = OnCinema, fontWeight = FontWeight.SemiBold)
                Text("${DvrKind.label(active.contentKind)} · ${active.channelName}", color = OnCinemaMuted)
                Button(onClick = { DvrActions.stop(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop recording")
                }
            }
        }
        if (snapshot.schedules.isNotEmpty()) {
            item { Text("Scheduled", style = MaterialTheme.typography.titleMedium, color = OnCinema) }
            items(snapshot.schedules, key = { it.id }) { sched ->
                Column(Modifier.fillMaxWidth()) {
                    Text(sched.title, color = OnCinema, fontWeight = FontWeight.Medium)
                    Text("${DvrKind.label(sched.contentKind)} · ${sched.channelName} · ${timeFmt.format(Date(sched.startMs))}", color = OnCinemaMuted, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { dvr.cancelSchedule(sched.id) }) { Text("Cancel") }
                }
            }
        }
        item { Text("Library", style = MaterialTheme.typography.titleMedium, color = OnCinema) }
        val library = snapshot.recordings.filterNot { it.isActive() }
        if (library.isEmpty()) {
            item {
                Text(
                    "No recordings yet. Record Live, a series episode, or a movie. Files stay on this phone.",
                    color = OnCinemaMuted
                )
            }
        } else {
            items(library, key = { it.id }) { rec ->
                RecordingPhoneRow(
                    rec = rec,
                    timeLabel = timeFmt.format(Date(rec.startMs)),
                    onPlay = { playRecording(onPlay, rec) },
                    onDelete = {
                        dvr.deleteRecording(rec.id)
                        Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
private fun RecordingPhoneRow(
    rec: RecordingEntry,
    timeLabel: String,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(rec.title, color = OnCinema, fontWeight = FontWeight.Medium)
        Text(
            "${DvrKind.label(rec.contentKind)} · ${rec.channelName} · $timeLabel · ${rec.statusEnum().name.lowercase()}",
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rec.playable()) {
                Button(onClick = onPlay) { Text("Play") }
            }
            OutlinedButton(onClick = onDelete) { Text("Delete") }
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
            kind = ContentKind.VOD
        )
    )
}
