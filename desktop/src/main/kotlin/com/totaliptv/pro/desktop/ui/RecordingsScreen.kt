package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.desktop.data.GuideTime
import com.totaliptv.pro.desktop.dvr.DvrRecorder
import com.totaliptv.pro.desktop.dvr.RecordingEntry
import com.totaliptv.pro.desktop.dvr.RecordingStatus
import com.totaliptv.pro.desktop.dvr.ScheduledRecording

@Composable
fun RecordingsScreen(
    snapshot: DvrRecorder.Snapshot,
    onPlay: (RecordingEntry) -> Unit,
    onDelete: (RecordingEntry) -> Unit,
    onStop: () -> Unit,
    onCancelSchedule: (ScheduledRecording) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VideoLibrary, null, tint = TipBlue)
            Spacer(Modifier.width(8.dp))
            Text("Recordings", style = MaterialTheme.typography.headlineMedium, color = TipOnBg, modifier = Modifier.weight(1f))
            if (snapshot.active != null) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = TipBlue, contentColor = TipOnAmber)
                ) {
                    Icon(Icons.Default.Stop, null, tint = TipOnAmber)
                    Spacer(Modifier.width(6.dp))
                    Text("Stop recording", color = TipOnAmber, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Saved on this computer only — ${snapshot.recordingsDir}",
            style = MaterialTheme.typography.bodyMedium,
            color = TipMuted
        )
        Text(snapshot.engineHint, style = MaterialTheme.typography.bodyMedium, color = TipMuted)
        snapshot.active?.let { active ->
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TipBlue.copy(alpha = 0.2f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.FiberManualRecord, null, tint = TipAccent)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Recording now", color = TipAccent, fontWeight = FontWeight.Bold)
                    Text("${active.channelName} · ${active.title}", color = TipOnBg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (snapshot.schedules.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Scheduled", style = MaterialTheme.typography.titleMedium, color = TipOnBg)
            Spacer(Modifier.height(8.dp))
            snapshot.schedules.forEach { sched ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TipSurface)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sched.title, fontWeight = FontWeight.Medium, color = TipOnBg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${sched.channelName} · ${GuideTime.formatRange(sched.startMs, sched.endMs)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TipMuted
                        )
                    }
                    TextButton(onClick = { onCancelSchedule(sched) }) { Text("Cancel") }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Library", style = MaterialTheme.typography.titleMedium, color = TipOnBg)
        Spacer(Modifier.height(8.dp))
        val library = snapshot.recordings.filterNot { it.isActive() }
        if (library.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(TipSurface)
                    .padding(16.dp)
            ) {
                Text(
                    "No recordings yet. Use Record now on Live TV or TV Guide. Files stay on this PC.",
                    color = TipMuted
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                items(library, key = { it.id }) { rec ->
                    RecordingRow(rec, onPlay = { onPlay(rec) }, onDelete = { onDelete(rec) })
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    rec: RecordingEntry,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TipSurface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(rec.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, color = TipOnBg)
            Text(
                "${rec.channelName} · ${GuideTime.formatTime(rec.startMs)} · ${formatDuration(rec.durationMs)} · ${rec.statusEnum().name.lowercase()}",
                style = MaterialTheme.typography.bodyMedium,
                color = TipMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (rec.playable()) {
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = TipAccent)
            }
        }
        if (rec.statusEnum() != RecordingStatus.RECORDING) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TipMuted)
            }
        }
    }
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "—"
    val totalSec = ms / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
