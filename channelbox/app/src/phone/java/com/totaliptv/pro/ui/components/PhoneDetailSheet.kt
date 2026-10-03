package com.totaliptv.pro.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import com.totaliptv.pro.ui.theme.LocalTipColors
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.local.WatchProgressStore
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.model.WatchProgress
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.data.xtream.XtreamApi
import com.totaliptv.pro.dvr.DvrRecordUi
import com.totaliptv.pro.ui.theme.CinemaSurfaceHigh
import com.totaliptv.pro.ui.theme.LiveMarker
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import com.totaliptv.pro.util.YoutubePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneDetailSheet(
    item: MediaItem,
    repository: CatalogRepository,
    onDismiss: () -> Unit,
    onPlay: (MediaItem) -> Unit,
    onPlayFromStart: (MediaItem) -> Unit,
    watchProgress: WatchProgress? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var enriched by remember(item.id) { mutableStateOf(item) }
    var progress by remember(item.id) { mutableStateOf(watchProgress) }
    var isFavorite by remember(item.id) { mutableStateOf(false) }
    var resolvingPreview by remember { mutableStateOf(false) }
    var seriesInfo by remember(item.id) { mutableStateOf<XtreamApi.SeriesInfo?>(null) }
    var seriesLoading by remember(item.id) { mutableStateOf(item.kind == ContentKind.SERIES) }
    var selectedSeason by remember(item.id) { mutableIntStateOf(1) }
    var selectedEpisodeId by remember(item.id) { mutableIntStateOf(-1) }

    val isSeriesPicker = item.kind == ContentKind.SERIES && !item.id.startsWith("series-ep-")

    LaunchedEffect(item.id) {
        isFavorite = repository.isFavorite(item.id)
        if (watchProgress == null) {
            progress = runCatching {
                val store = (context.applicationContext as? TotalIptvProApp)?.watchProgress
                    ?: WatchProgressStore(context.applicationContext)
                store.forCatalogItem(item.id)
            }.getOrNull()
        } else {
            progress = watchProgress
        }
        enriched = repository.resolvePoster(item)
        if (isSeriesPicker) {
            seriesLoading = true
            val info = withContext(Dispatchers.IO) {
                runCatching { repository.resolveSeriesInfo(item) }.getOrNull()
            }
            seriesInfo = info
            val first = info?.firstEpisode()
            if (first != null) {
                selectedSeason = first.season
                selectedEpisodeId = first.episodeId
            }
            seriesLoading = false
        }
    }

    val canResume = progress?.shouldResume() == true
    val seasonEpisodes = seriesInfo?.seasons?.firstOrNull { it.season == selectedSeason }?.episodes.orEmpty()
    val dvrSnap by remember(context) {
        (context.applicationContext as TotalIptvProApp).dvr.snapshot
    }.collectAsState()
    val recordLook = DvrRecordUi.appearance(
        dvrSnap.active,
        if (isSeriesPicker && selectedEpisodeId > 0) "series-ep-$selectedEpisodeId" else enriched.id,
        enriched.streamUrl,
        if (isSeriesPicker) DvrRecordUi.IDLE_EPISODE_LABEL else DvrRecordUi.IDLE_LABEL
    )

    fun playSelected(startOver: Boolean) {
        if (isSeriesPicker && selectedEpisodeId > 0) {
            scope.launch {
                val ep = withContext(Dispatchers.IO) {
                    repository.resolveSeriesEpisodePlayable(enriched, selectedEpisodeId)
                }
                if (ep == null) {
                    Toast.makeText(context, "No playable episode", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (startOver) onPlayFromStart(ep) else onPlay(ep)
                onDismiss()
            }
        } else {
            if (startOver) onPlayFromStart(enriched) else onPlay(enriched)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = if (LocalTipColors.current.isDark) Color(0xFF000000) else MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CinemaSurfaceHigh)
                ) {
                    val art = enriched.artworkUrl()
                    if (!art.isNullOrBlank()) {
                        AsyncImage(
                            model = art,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = enriched.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OnCinema,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when (enriched.kind) {
                            ContentKind.LIVE -> "Live TV"
                            ContentKind.VOD -> "Movie"
                            ContentKind.SERIES -> "Series"
                        },
                        color = OnCinemaMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    enriched.displayRating()?.let {
                        Text("Rating $it", color = OnCinemaMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (canResume && progress != null) {
                        Spacer(Modifier.height(10.dp))
                        val frac = progress!!.fraction()
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                        Text(
                            text = "${(progress!!.fraction() * 100).toInt()}% watched",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnCinemaMuted,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            if (isSeriesPicker) {
                Spacer(Modifier.height(16.dp))
                if (seriesLoading) {
                    Text("Loading seasons…", color = OnCinemaMuted)
                } else if (seriesInfo == null || seriesInfo!!.seasons.isEmpty()) {
                    Text("No episodes found for this series.", color = OnCinemaMuted)
                } else {
                    Text("Season", fontWeight = FontWeight.SemiBold, color = OnCinemaMuted)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        seriesInfo!!.seasons.forEach { season ->
                            FilterChip(
                                selected = selectedSeason == season.season,
                                onClick = {
                                    selectedSeason = season.season
                                    selectedEpisodeId = season.episodes.firstOrNull()?.episodeId ?: -1
                                },
                                label = { Text("S${season.season}") }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Episodes", fontWeight = FontWeight.SemiBold, color = OnCinemaMuted)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(seasonEpisodes, key = { it.episodeId }) { ep ->
                            FilterChip(
                                selected = selectedEpisodeId == ep.episodeId,
                                onClick = { selectedEpisodeId = ep.episodeId },
                                label = {
                                    Text(
                                        "E${ep.episodeNum}  ${ep.title}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            if (canResume && !isSeriesPicker) {
                Button(
                    onClick = { onPlay(enriched); onDismiss() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Resume")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { onPlayFromStart(enriched); onDismiss() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Play from start")
                }
            } else if (canResume && isSeriesPicker) {
                Button(
                    onClick = { onPlay(enriched); onDismiss() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Resume")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { playSelected(startOver = true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Play selected episode")
                }
            } else {
                Button(
                    onClick = { playSelected(startOver = false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isSeriesPicker) "Play episode" else "Play")
                }
            }

            Spacer(Modifier.height(10.dp))
            if (recordLook.selected) {
                Button(
                    onClick = {
                        com.totaliptv.pro.dvr.DvrActions.stop(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LiveMarker,
                        contentColor = OnCinema
                    )
                ) {
                    Text(recordLook.label, fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(
                    onClick = {
                        if (isSeriesPicker && selectedEpisodeId > 0) {
                            scope.launch {
                                val ep = withContext(Dispatchers.IO) {
                                    repository.resolveSeriesEpisodePlayable(enriched, selectedEpisodeId)
                                }
                                if (ep == null) {
                                    Toast.makeText(context, "No playable episode", Toast.LENGTH_SHORT).show()
                                } else {
                                    com.totaliptv.pro.dvr.DvrActions.recordNow(context, ep)
                                }
                            }
                        } else {
                            com.totaliptv.pro.dvr.DvrActions.recordNow(context, enriched)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(recordLook.label)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            repository.toggleFavorite(enriched)
                            isFavorite = repository.isFavorite(enriched.id)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isFavorite) "Favorited" else "Favorite")
                }
                if (enriched.kind != ContentKind.LIVE) {
                    OutlinedButton(
                        onClick = {
                            if (resolvingPreview) return@OutlinedButton
                            resolvingPreview = true
                            scope.launch {
                                try {
                                    val trailer = repository.resolveYoutubeTrailer(enriched)
                                        ?: enriched.youtubeTrailer
                                    YoutubePreview.openTrailer(context, trailer)
                                } catch (t: Throwable) {
                                    Toast.makeText(
                                        context,
                                        "Preview failed: ${com.totaliptv.pro.util.SensitiveText.forUser(t)}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } finally {
                                    resolvingPreview = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !resolvingPreview
                    ) {
                        Text(if (resolvingPreview) "…" else "Preview")
                    }
                }
            }
        }
    }
}
