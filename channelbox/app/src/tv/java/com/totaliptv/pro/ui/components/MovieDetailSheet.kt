package com.totaliptv.pro.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.local.WatchProgressStore
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.model.WatchProgress
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.data.xtream.XtreamApi
import com.totaliptv.pro.ui.theme.BrandBlue
import com.totaliptv.pro.ui.theme.CinemaBgElevated
import com.totaliptv.pro.ui.theme.FocusBorder
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import com.totaliptv.pro.util.YoutubePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Movie / series detail overlay (TV).
 * Series: Season chip row → episode list → Play selected episode.
 * When watch progress exists: Resume + Restart side by side.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MovieDetailSheet(
    item: MediaItem,
    isFavorite: Boolean,
    repository: CatalogRepository,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    onResume: (() -> Unit)? = null,
    onPlayFromStart: (() -> Unit)? = null,
    watchProgress: WatchProgress? = null,
    /** Prefer this for series episode picks (and movies). Falls back to [onPlay]. */
    onPlayItem: ((MediaItem) -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    BackHandler {
        runCatching { focusManager.clearFocus(force = true) }
        onDismiss()
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    var resolvingPreview by remember { mutableStateOf(false) }
    var enriched by remember(item.id) { mutableStateOf(item) }
    var progress by remember(item.id) { mutableStateOf(watchProgress) }
    var seriesInfo by remember(item.id) { mutableStateOf<XtreamApi.SeriesInfo?>(null) }
    var seriesLoading by remember(item.id) { mutableStateOf(item.kind == ContentKind.SERIES) }
    var selectedSeason by remember(item.id) { mutableIntStateOf(1) }
    var selectedEpisodeId by remember(item.id) { mutableIntStateOf(-1) }

    fun progressStore(): WatchProgressStore =
        (context.applicationContext as? TotalIptvProApp)?.watchProgress
            ?: WatchProgressStore(context.applicationContext)

    fun reloadProgress() {
        val passed = watchProgress
        if (passed != null && passed.shouldResume()) {
            progress = passed
            return
        }
        progress = runCatching { progressStore().forCatalogItem(item.id) }.getOrNull()
            ?: passed
    }

    LaunchedEffect(item.id, watchProgress) {
        reloadProgress()
        val updated = withContext(Dispatchers.IO) {
            runCatching { repository.resolvePoster(item) }.getOrDefault(item)
        }
        enriched = updated
        if (item.kind == ContentKind.SERIES && !item.id.startsWith("series-ep-")) {
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
        runCatching { playFocus.requestFocus() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, item.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadProgress()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val canResume = progress?.shouldResume() == true
    val pct = progress?.let { (it.fraction() * 100).toInt().coerceIn(1, 99) }
    val seasonEpisodes = seriesInfo?.seasons?.firstOrNull { it.season == selectedSeason }?.episodes.orEmpty()
    val isSeriesPicker = enriched.kind == ContentKind.SERIES && !enriched.id.startsWith("series-ep-")

    fun playResolved(startOver: Boolean) {
        if (isSeriesPicker && selectedEpisodeId > 0) {
            scope.launch {
                val ep = withContext(Dispatchers.IO) {
                    repository.resolveSeriesEpisodePlayable(enriched, selectedEpisodeId)
                }
                if (ep != null && onPlayItem != null) {
                    onPlayItem(ep)
                } else if (ep != null) {
                    // Parent only has unit callback — still invoke play after parent resolves;
                    // stash via onPlayItem when provided. Fallback: unit onPlay.
                    onPlay()
                } else {
                    android.widget.Toast.makeText(
                        context, "No playable episode", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            return
        }
        if (onPlayItem != null) {
            onPlayItem(enriched)
        } else if (startOver) {
            (onPlayFromStart ?: onPlay).invoke()
        } else {
            onPlay()
        }
    }

    fun runPreview() {
        if (resolvingPreview) return
        resolvingPreview = true
        scope.launch {
            try {
                val trailer = repository.resolveYoutubeTrailer(enriched)
                    ?: enriched.youtubeTrailer
                if (!trailer.isNullOrBlank() && trailer != enriched.youtubeTrailer) {
                    enriched = enriched.copy(youtubeTrailer = trailer)
                }
                YoutubePreview.openTrailer(context, trailer)
            } catch (t: Throwable) {
                android.util.Log.e("TotalIPTV.Preview", "runPreview failed", t)
                android.widget.Toast.makeText(
                    context,
                    "Preview failed: ${t.message ?: "error"}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } finally {
                resolvingPreview = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE607090D)),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .fillMaxHeight(0.88f)
                .background(CinemaBgElevated, RoundedCornerShape(16.dp))
                .border(2.dp, FocusBorder.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(168.dp)
                    .aspectRatio(2f / 3f)
                    .background(Color(0xFF0C1018), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                NetworkImage(
                    url = enriched.artworkUrl(),
                    contentDescription = enriched.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    placeholderLabel = enriched.name.take(1).uppercase()
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (enriched.kind == ContentKind.SERIES) "SERIES" else "MOVIE",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandBlue,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = enriched.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = OnCinema,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(
                    enriched.groupTitle?.takeIf { it.isNotBlank() },
                    enriched.displayRating()?.let { "★ $it" },
                    if (canResume && pct != null) "Resume at $pct%" else null
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnCinemaMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val plotText = enriched.plot?.trim()?.takeIf { it.isNotBlank() }
                if (plotText != null && !isSeriesPicker) {
                    Text(
                        text = plotText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnCinema.copy(alpha = 0.92f),
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                if (isSeriesPicker) {
                    if (seriesLoading) {
                        Text(
                            text = "Loading seasons…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnCinemaMuted
                        )
                    } else if (seriesInfo == null || seriesInfo!!.seasons.isEmpty()) {
                        Text(
                            text = "No episodes found for this series.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnCinemaMuted
                        )
                    } else {
                        Text(
                            text = "Season",
                            style = MaterialTheme.typography.labelMedium,
                            color = OnCinemaMuted,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            seriesInfo!!.seasons.forEach { season ->
                                TopBarChip(
                                    label = "S${season.season}",
                                    onClick = {
                                        selectedSeason = season.season
                                        selectedEpisodeId = season.episodes.firstOrNull()?.episodeId ?: -1
                                    },
                                    emphasized = selectedSeason == season.season
                                )
                            }
                        }
                        Text(
                            text = "Episodes",
                            style = MaterialTheme.typography.labelMedium,
                            color = OnCinemaMuted,
                            fontWeight = FontWeight.SemiBold
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = true)
                                .heightIn(min = 80.dp, max = 220.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(seasonEpisodes, key = { it.episodeId }) { ep ->
                                TopBarChip(
                                    label = "E${ep.episodeNum}  ${ep.title}",
                                    onClick = { selectedEpisodeId = ep.episodeId },
                                    emphasized = selectedEpisodeId == ep.episodeId,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (canResume && !isSeriesPicker) {
                        TopBarChip(
                            label = if (pct != null) "▶ Resume ($pct%)" else "▶ Resume",
                            onClick = { (onResume ?: onPlay).invoke() },
                            emphasized = true,
                            modifier = Modifier.focusRequester(playFocus)
                        )
                        TopBarChip(
                            label = "Restart",
                            onClick = { (onPlayFromStart ?: onPlay).invoke() },
                            emphasized = false
                        )
                    } else if (canResume && isSeriesPicker) {
                        TopBarChip(
                            label = if (pct != null) "▶ Resume ($pct%)" else "▶ Resume",
                            onClick = { (onResume ?: onPlay).invoke() },
                            emphasized = true,
                            modifier = Modifier.focusRequester(playFocus)
                        )
                        TopBarChip(
                            label = "▶ Play episode",
                            onClick = { playResolved(startOver = true) },
                            emphasized = false
                        )
                    } else {
                        TopBarChip(
                            label = if (isSeriesPicker) "▶ Play episode" else "▶ Play",
                            onClick = { playResolved(startOver = false) },
                            emphasized = true,
                            modifier = Modifier.focusRequester(playFocus)
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TopBarChip(
                        label = if (resolvingPreview) "Preview…" else "Preview",
                        onClick = { runPreview() },
                        emphasized = false
                    )
                    TopBarChip(
                        label = if (isFavorite) "★ Favorited" else "☆ Favorite",
                        onClick = onToggleFavorite,
                        emphasized = false
                    )
                    TopBarChip(
                        label = if (isSeriesPicker) "Record episode" else "Record",
                        onClick = {
                            if (isSeriesPicker && selectedEpisodeId > 0) {
                                scope.launch {
                                    val ep = withContext(Dispatchers.IO) {
                                        repository.resolveSeriesEpisodePlayable(enriched, selectedEpisodeId)
                                    }
                                    if (ep == null) {
                                        android.widget.Toast.makeText(
                                            context, "No playable episode", android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        com.totaliptv.pro.dvr.DvrActions.recordNow(context, ep)
                                    }
                                }
                            } else {
                                com.totaliptv.pro.dvr.DvrActions.recordNow(context, enriched)
                            }
                        },
                        emphasized = false
                    )
                    TopBarChip(
                        label = "Close",
                        onClick = onDismiss,
                        emphasized = false
                    )
                }
            }
        }
    }
}
