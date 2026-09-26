package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.desktop.artwork.ArtworkRole
import com.totaliptv.pro.desktop.data.Category
import com.totaliptv.pro.desktop.data.ChannelEpg
import com.totaliptv.pro.desktop.data.EpgProgram
import com.totaliptv.pro.desktop.data.GuideKeys
import com.totaliptv.pro.desktop.data.GuideTime
import com.totaliptv.pro.desktop.data.LiveChannelMapping
import com.totaliptv.pro.desktop.data.LiveEpgBinding
import com.totaliptv.pro.desktop.data.MediaItem
import com.totaliptv.pro.desktop.dvr.DvrRecordUi
import com.totaliptv.pro.desktop.dvr.RecordingEntry

/**
 * Live TV guide: category chips, channel list, and program timeline.
 * Clock, hour ticks, program ranges, and the now-line use [GuideTime]
 * (OS default zone). Settings can add a manual hour offset on top of that.
 * Selecting a program/channel plays that live stream.
 */
@Composable
fun GuideScreen(
    channels: List<MediaItem>,
    categories: List<Category>,
    epgByStreamId: Map<Int, ChannelEpg>,
    epgLoadingIds: Set<Int>,
    playingTitle: String?,
    guideStyle: String = "current",
    selectedCategoryId: String? = null,
    onCategoryChange: (String?) -> Unit = {},
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onNeedEpg: (MediaItem) -> Unit,
    onPlayChannel: (MediaItem) -> Unit,
    onOpenSplit: (MediaItem) -> Unit = {},
    onStop: () -> Unit,
    recordingTitle: String? = null,
    activeRecording: RecordingEntry? = null,
    onRecordNow: (MediaItem, String?, Long?) -> Unit = { _, _, _ -> },
    onScheduleProgram: (MediaItem, String, Long, Long) -> Unit = { _, _, _, _ -> },
    onStopRecording: () -> Unit = {}
) {
    val classic = guideStyle.equals("classic", ignoreCase = true)

    var selectedChannelId by remember { mutableStateOf<String?>(null) }

    val filtered = remember(channels, selectedCategoryId, query) {
        LiveChannelMapping.filterLiveChannels(channels, selectedCategoryId, query)
    }

    val selected = filtered.find { it.id == selectedChannelId } ?: filtered.firstOrNull()
    LaunchedEffect(selected?.id) {
        selectedChannelId = selected?.id
        selected?.let { onNeedEpg(it) }
    }

    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            tick++
        }
    }
    val liveNow = remember(tick) { GuideTime.nowMs() }

    Column(Modifier.fillMaxSize().tvContentBackground().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Schedule, null, tint = TipBlue)
            Spacer(Modifier.width(8.dp))
            Text(if (classic) "TV Guide · Classic" else "TV Guide", style = MaterialTheme.typography.headlineMedium, color = TipOnBg, modifier = Modifier.weight(1f))
            Text(
                GuideTime.formatClock(liveNow),
                style = MaterialTheme.typography.bodyMedium
            )
            if (recordingTitle != null) {
                Spacer(Modifier.width(12.dp))
                Text(recordingTitle, color = TipAccent, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onStopRecording) { Text("Stop recording") }
            }
            if (playingTitle != null) {
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onStop) { Text("Stop player") }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Filter channels…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().trackTextInputFocus(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TipBlue,
                unfocusedBorderColor = TipSurfaceAlt,
                focusedContainerColor = TipSurface,
                unfocusedContainerColor = TipSurface,
                focusedTextColor = TipOnBg,
                unfocusedTextColor = TipOnBg,
                cursorColor = TipBlue,
                focusedPlaceholderColor = TipMuted,
                unfocusedPlaceholderColor = TipMuted
            )
        )
        Spacer(Modifier.height(10.dp))
        if (categories.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedCategoryId == null,
                        onClick = { onCategoryChange(null) },
                        label = { Text("All") }
                    )
                }
                items(categories, key = { it.id }) { cat ->
                    FilterChip(
                        selected = selectedCategoryId == cat.id,
                        onClick = { onCategoryChange(cat.id) },
                        label = { Text(cat.name, maxLines = 1) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No live channels in this filter.", color = TipMuted)
            }
            return
        }

        if (classic) {
            ClassicGuideGrid(
                channels = filtered,
                epgByStreamId = epgByStreamId,
                epgLoadingIds = epgLoadingIds,
                liveNow = liveNow,
                selectedChannelId = selected?.id,
                onSelectChannel = { ch ->
                    selectedChannelId = ch.id
                    onNeedEpg(ch)
                },
                onNeedEpg = onNeedEpg,
                onPlayChannel = onPlayChannel
            )
            return
        }

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Channel list
            LazyColumn(
                Modifier
                    .width(if (classic) 360.dp else 280.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TipSurface)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filtered, key = { it.id }) { ch ->
                    val sel = ch.id == selected?.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) TipBlue.copy(alpha = 0.25f) else TipSurfaceAlt)
                            .clickable {
                                selectedChannelId = ch.id
                                onNeedEpg(ch)
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RemoteArtwork(
                            role = ArtworkRole.LOGO,
                            url = ch.logoUrl,
                            contentDescription = ch.name,
                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
                            fallbackIcon = Icons.Default.LiveTv,
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            ch.name,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            color = if (sel) TipBlue else TipOnBg,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Program panel + mini timeline for nearby channels
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (selected != null) {
                    val sid = GuideKeys.of(selected)
                    val epg = sid?.let { epgByStreamId[it] }
                    val loading = sid != null && sid in epgLoadingIds
                    val programs = LiveEpgBinding.bindForDisplay(
                        channel = selected,
                        programs = epg?.programs.orEmpty(),
                        siblings = filtered,
                        nowMs = liveNow
                    )

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(TipSurface)
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RemoteArtwork(
                            role = ArtworkRole.LOGO,
                            url = selected.logoUrl,
                            contentDescription = selected.name,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                            fallbackIcon = Icons.Default.LiveTv,
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(selected.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TipOnBg)
                            val nowProg = programs.find { it.contains(liveNow) }
                            Text(
                                nowProg?.let { "Now: ${it.title}" } ?: selected.groupTitle ?: "Live",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = TipAccent
                            )
                        }
                        Button(
                            onClick = { onPlayChannel(selected) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TipBlue,
                                contentColor = TipOnAmber
                            )
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = TipOnAmber)
                            Spacer(Modifier.width(6.dp))
                            Text("Watch", color = TipOnAmber, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { onOpenSplit(selected) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TipOnBg),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TipBlue)
                        ) {
                            Icon(Icons.Default.VerticalSplit, contentDescription = null, tint = TipBlue)
                            Spacer(Modifier.width(6.dp))
                            Text("Split", color = TipOnBg)
                        }
                        Spacer(Modifier.width(8.dp))
                        RecordControlButton(
                            active = activeRecording,
                            itemId = selected.id,
                            streamUrl = selected.streamUrl,
                            onClick = {
                                val nowProg = programs.find { it.contains(liveNow) }
                                onRecordNow(selected, nowProg?.title, nowProg?.endMs)
                            },
                            idleLabel = "Record now"
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        if (classic) "What's on" else "Schedule",
                        style = MaterialTheme.typography.titleMedium,
                        color = TipOnBg
                    )
                    Spacer(Modifier.height(8.dp))

                    when {
                        loading && programs.isEmpty() -> {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = TipBlue)
                            }
                        }
                        programs.isEmpty() -> {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Text(
                                    "No EPG for this channel (provider may not publish guide data).",
                                    color = TipMuted
                                )
                            }
                        }
                        else -> {
                            if (!classic) {
                                // Current: horizontal timeline strip for selected channel
                                val windowStart = liveNow - 30 * 60_000L
                                val windowEnd = liveNow + 3 * 60 * 60_000L
                                val windowPrograms = programs.filter { it.endMs > windowStart && it.startMs < windowEnd }
                                val scroll = rememberScrollState()
                                val pxPerMin = 2.2f
                                val totalMin = ((windowEnd - windowStart) / 60_000L).toFloat().coerceAtLeast(1f)
                                val timelineWidth = (totalMin * pxPerMin).dp

                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(TipSurface)
                                        .padding(12.dp)
                                ) {
                                    Row(Modifier.horizontalScroll(scroll)) {
                                        Box(Modifier.width(timelineWidth).height(22.dp)) {
                                            GuideTime.hourTicks(windowStart, windowEnd).forEach { t ->
                                                val x = ((t - windowStart) / 60_000.0 * pxPerMin).toFloat()
                                                Text(
                                                    GuideTime.formatHourTick(t),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier.offset(x = x.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(Modifier.horizontalScroll(scroll)) {
                                        Box(Modifier.width(timelineWidth).height(56.dp)) {
                                            windowPrograms.forEach { prog ->
                                                val start = prog.startMs.coerceAtLeast(windowStart)
                                                val end = prog.endMs.coerceAtMost(windowEnd)
                                                val x = ((start - windowStart) / 60_000.0 * pxPerMin).toFloat()
                                                val w = ((end - start) / 60_000.0 * pxPerMin).toFloat().coerceAtLeast(40f)
                                                val isNow = prog.contains(liveNow)
                                                Box(
                                                    Modifier
                                                        .offset(x = x.dp)
                                                        .width(w.dp)
                                                        .fillMaxHeight()
                                                        .padding(end = 3.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isNow) TipBlue.copy(alpha = 0.45f) else TipSurfaceAlt)
                                                        .border(
                                                            width = if (isNow) 1.dp else 0.dp,
                                                            color = TipAccent,
                                                            shape = RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable { onPlayChannel(selected) }
                                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                                ) {
                                                    Column {
                                                        Text(
                                                            prog.title,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            fontWeight = FontWeight.Medium,
                                                            style = MaterialTheme.typography.bodyMedium
                                                        )
                                                        Text(
                                                            GuideTime.formatRange(prog.startMs, prog.endMs),
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = TipMuted,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                            }
                                            val nowX = ((liveNow - windowStart) / 60_000.0 * pxPerMin).toFloat()
                                            Box(
                                                Modifier
                                                    .offset(x = nowX.dp)
                                                    .width(2.dp)
                                                    .fillMaxHeight()
                                                    .background(TipAccent)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }

                            // Classic = schedule list only (Shield-style). Current = list under timeline.
                            LazyColumn(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(if (classic) 4.dp else 6.dp)
                            ) {
                                items(programs, key = { it.id }) { prog ->
                                    ProgramRow(
                                        program = prog,
                                        isNow = prog.contains(liveNow),
                                        onClick = { onPlayChannel(selected) },
                                        onRecord = {
                                            if (prog.contains(liveNow)) onRecordNow(selected, prog.title, prog.endMs)
                                            else if (prog.endMs > liveNow) onScheduleProgram(selected, prog.title, prog.startMs, prog.endMs)
                                            else onRecordNow(selected, prog.title, null)
                                        },
                                        recordLabel = if (prog.contains(liveNow)) "Record" else if (prog.endMs > liveNow) "Schedule" else "Record",
                                        recordActive = prog.contains(liveNow) &&
                                            DvrRecordUi.matches(activeRecording, selected.id, selected.streamUrl)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}



@Composable
private fun ClassicGuideGrid(
    channels: List<MediaItem>,
    epgByStreamId: Map<Int, ChannelEpg>,
    epgLoadingIds: Set<Int>,
    liveNow: Long,
    selectedChannelId: String?,
    onSelectChannel: (MediaItem) -> Unit,
    onNeedEpg: (MediaItem) -> Unit,
    onPlayChannel: (MediaItem) -> Unit
) {
    val windowStart = liveNow - 15 * 60_000L
    val windowEnd = liveNow + 3 * 60 * 60_000L
    val pxPerMin = 2.8f
    val totalMin = ((windowEnd - windowStart) / 60_000L).toFloat().coerceAtLeast(1f)
    val timelineWidth = (totalMin * pxPerMin).dp
    val hScroll = rememberScrollState()
    val visible = channels

    // Prefetch EPG for first rows
    LaunchedEffect(visible.map { it.id }.joinToString()) {
        visible.take(24).forEach { onNeedEpg(it) }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Classic grid — scroll sideways for the next 3 hours. Tap a show to watch.",
            color = TipMuted,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        // Time header
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(200.dp))
            Row(Modifier.weight(1f).horizontalScroll(hScroll)) {
                Box(Modifier.width(timelineWidth).height(24.dp)) {
                    GuideTime.hourTicks(windowStart, windowEnd).forEach { t ->
                        val x = ((t - windowStart) / 60_000.0 * pxPerMin).toFloat()
                        Text(
                            GuideTime.formatHourTick(t),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TipOnBg,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.offset(x = x.dp)
                        )
                    }
                    val nowX = ((liveNow - windowStart) / 60_000.0 * pxPerMin).toFloat()
                    Box(Modifier.offset(x = nowX.dp).width(2.dp).fillMaxHeight().background(TipAccent))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(visible, key = { it.id }) { ch ->
                val sid = GuideKeys.of(ch)
                val programs = LiveEpgBinding.bindForDisplay(
                    channel = ch,
                    programs = sid?.let { epgByStreamId[it]?.programs }.orEmpty(),
                    siblings = channels,
                    nowMs = liveNow
                )
                val loading = sid != null && sid in epgLoadingIds
                val sel = ch.id == selectedChannelId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) TipBlue.copy(alpha = 0.18f) else TipSurface)
                        .clickable { onSelectChannel(ch) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier
                            .width(200.dp)
                            .fillMaxHeight()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RemoteArtwork(
                            role = ArtworkRole.LOGO,
                            url = ch.logoUrl,
                            contentDescription = ch.name,
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)),
                            fallbackIcon = Icons.Default.LiveTv,
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            ch.name,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (sel) TipBlue else TipOnBg,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(Modifier.weight(1f).horizontalScroll(hScroll).fillMaxHeight()) {
                        Box(Modifier.width(timelineWidth).fillMaxHeight().padding(vertical = 4.dp)) {
                            when {
                                loading && programs.isEmpty() -> {
                                    Text("Loading…", color = TipMuted, modifier = Modifier.padding(8.dp))
                                }
                                programs.isEmpty() -> {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .padding(end = 4.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(TipSurfaceAlt)
                                            .clickable { onPlayChannel(ch) }
                                            .padding(8.dp)
                                    ) {
                                        Text("Live · no EPG", color = TipMuted, maxLines = 1)
                                    }
                                }
                                else -> {
                                    programs.filter { it.endMs > windowStart && it.startMs < windowEnd }.forEach { prog ->
                                        val start = prog.startMs.coerceAtLeast(windowStart)
                                        val end = prog.endMs.coerceAtMost(windowEnd)
                                        val x = ((start - windowStart) / 60_000.0 * pxPerMin).toFloat()
                                        val w = ((end - start) / 60_000.0 * pxPerMin).toFloat().coerceAtLeast(48f)
                                        val isNow = prog.contains(liveNow)
                                        Box(
                                            Modifier
                                                .offset(x = x.dp)
                                                .width(w.dp)
                                                .fillMaxHeight()
                                                .padding(end = 3.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isNow) TipBlue else TipSurfaceAlt)
                                                .clickable {
                                                    onSelectChannel(ch)
                                                    onPlayChannel(ch)
                                                }
                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                prog.title,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                color = if (isNow) TipOnAmber else TipOnBg,
                                                fontWeight = if (isNow) FontWeight.Bold else FontWeight.Medium,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                    val nowX = ((liveNow - windowStart) / 60_000.0 * pxPerMin).toFloat()
                                    Box(
                                        Modifier
                                            .offset(x = nowX.dp)
                                            .width(2.dp)
                                            .fillMaxHeight()
                                            .background(TipAccent)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgramRow(
    program: EpgProgram,
    isNow: Boolean,
    onClick: () -> Unit,
    onRecord: () -> Unit = {},
    recordLabel: String = "Record",
    recordActive: Boolean = false
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isNow) TipBlue.copy(alpha = 0.2f) else TipSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(110.dp)) {
            Text(GuideTime.formatTime(program.startMs), fontWeight = FontWeight.Medium, color = TipOnBg)
            Text(GuideTime.formatTime(program.endMs), style = MaterialTheme.typography.bodyMedium, color = TipMuted)
        }
        Column(Modifier.weight(1f)) {
            Text(program.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, color = TipOnBg)
            if (!program.description.isNullOrBlank()) {
                Text(
                    program.description,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (isNow) {
            Text("NOW", color = TipAccent, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
        }
        if (recordActive) {
            Button(
                onClick = onRecord,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TipRecordActive,
                    contentColor = TipOnRecordActive
                )
            ) {
                Text(DvrRecordUi.ACTIVE_LABEL, color = TipOnRecordActive, fontWeight = FontWeight.Bold)
            }
        } else {
            TextButton(onClick = onRecord) { Text(recordLabel) }
        }
        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = TipAccent)
    }
}

