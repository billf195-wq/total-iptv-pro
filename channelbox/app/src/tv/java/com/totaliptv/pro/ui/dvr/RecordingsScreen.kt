package com.totaliptv.pro.ui.dvr

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.ui.home.HomeShelfFit
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.dvr.DvrActions
import com.totaliptv.pro.dvr.DvrKind
import com.totaliptv.pro.dvr.RecordingEntry
import com.totaliptv.pro.ui.components.ClassicBrandBar
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
    onBack: () -> Unit,
    /** False when a parent shell already applied [HomeShelfFit.pageTopOffset]. */
    applyPageInset: Boolean = true
) {
    var pendingDelete by remember { mutableStateOf<RecordingEntry?>(null) }
    var focusReturnId by remember { mutableStateOf<String?>(null) }
    val cardFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun cardReq(id: String): FocusRequester = cardFocus.getOrPut(id) { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    BackHandler(enabled = pendingDelete == null) { onBack() }
    val context = LocalContext.current
    val dvr = DvrActions.recorder(context)
    val snapshot by dvr.snapshot.collectAsState()
    val timeFmt = SimpleDateFormat("MMM d h:mm a", Locale.getDefault())

    LaunchedEffect(focusReturnId, snapshot.recordings) {
        val id = focusReturnId ?: return@LaunchedEffect
        val library = snapshot.recordings.filterNot { it.isActive() }
        val target = library.firstOrNull { it.id == id }?.id
        if (target != null) {
            runCatching { cardReq(target).requestFocus() }
        } else {
            runCatching { backFocus.requestFocus() }
        }
        focusReturnId = null
    }
    pendingDelete?.let { doomed ->
        val cancelFocus = remember(doomed.id) { FocusRequester() }
        Dialog(onDismissRequest = {
            focusReturnId = doomed.id
            pendingDelete = null
        }) {
            Column(Modifier.padding(24.dp)) {
                Text("Delete ${doomed.title}?", color = OnCinema)
                Spacer(Modifier.height(12.dp))
                androidx.tv.material3.Button(onClick = {
                    val library = snapshot.recordings.filterNot { it.isActive() }
                    val idx = library.indexOfFirst { it.id == doomed.id }
                    val next = library.getOrNull(idx + 1)?.id ?: library.getOrNull(idx - 1)?.id
                    dvr.deleteRecording(doomed.id)
                    pendingDelete = null
                    focusReturnId = next
                    Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                }) { Text("Delete") }
                Spacer(Modifier.height(8.dp))
                androidx.tv.material3.Button(
                    onClick = {
                        focusReturnId = doomed.id
                        pendingDelete = null
                    },
                    modifier = Modifier.focusRequester(cancelFocus)
                ) { Text("Cancel") }
            }
            LaunchedEffect(doomed.id) {
                kotlinx.coroutines.delay(60)
                runCatching { cancelFocus.requestFocus() }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (applyPageInset) {
            ClassicBrandBar()
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = if (applyPageInset) 16.dp else 0.dp,
                    end = if (applyPageInset) 16.dp else 0.dp,
                    top = if (applyPageInset) HomeShelfFit.pageTopOffset else 0.dp,
                    bottom = 8.dp
                )
        ) {
        if (applyPageInset) {
            androidx.tv.material3.Button(
                onClick = onBack,
                modifier = Modifier.focusRequester(backFocus)
            ) { Text("Back") }
            Spacer(Modifier.height(HomeShelfFit.desktopRowGap))
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
                        focusRequester = cardReq(rec.id),
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
