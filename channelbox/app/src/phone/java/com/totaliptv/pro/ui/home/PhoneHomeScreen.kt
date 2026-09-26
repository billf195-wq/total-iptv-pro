package com.totaliptv.pro.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.util.SensitiveText
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.model.WatchProgress
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.ui.components.PhoneDetailSheet
import com.totaliptv.pro.ui.components.PhonePosterCard
import com.totaliptv.pro.ui.components.PhoneSectionTitle
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import com.totaliptv.pro.ui.theme.tipScreenBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PhoneHomeScreen(
    repository: CatalogRepository,
    onPlay: (MediaItem) -> Unit,
    onPlayFromStart: (MediaItem) -> Unit,
    onOpenLive: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeries: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val app = context.applicationContext as TotalIptvProApp
    val revision by repository.catalogRevision.collectAsState()
    val vodLoading by repository.vodLoading.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    var continueWatching by remember { mutableStateOf<List<WatchProgress>>(emptyList()) }
    var progressById by remember { mutableStateOf<Map<String, WatchProgress>>(emptyMap()) }
    var newlyMovies by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var newlySeries by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var liveSample by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var detail by remember { mutableStateOf<MediaItem?>(null) }

    fun reloadContinue() {
        val store = app.watchProgress
        continueWatching = store.continueWatching()
        progressById = store.progressByCatalogId()
    }

    LaunchedEffect(revision) {
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) { repository.ensureCatalogLoaded() }
        }.onFailure {
            error = SensitiveText.forUser(it)
        }
        newlyMovies = repository.newlyAddedMovies(24)
        newlySeries = repository.newlyAddedSeries(24)
        liveSample = repository.liveItems(20)
        warning = repository.lastWarning
        reloadContinue()
        loading = false
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadContinue()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tipScreenBrush())
            .padding(contentPadding)
    ) {
        when {
            loading && newlyMovies.isEmpty() && liveSample.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null && newlyMovies.isEmpty() && liveSample.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(error ?: "Error", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        // bump by forcing reload
                        loading = true
                        error = null
                    }) { Text("Retry") }
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Home",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = OnCinema,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    if (vodLoading) {
                        Text(
                            "Movies & series still loading…",
                            color = OnCinemaMuted,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    warning?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = OnCinemaMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }

                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(onClick = onOpenLive) { Text("Live") }
                        TextButton(onClick = onOpenMovies) { Text("Movies") }
                        TextButton(onClick = onOpenSeries) { Text("Series") }
                    }

                    if (continueWatching.isNotEmpty()) {
                        PhoneSectionTitle("Continue watching")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(continueWatching, key = { it.id }) { wp ->
                                PhonePosterCard(
                                    item = wp.toMediaItem(),
                                    progress = wp,
                                    onClick = {
                                        detail = wp.toMediaItem()
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (newlyMovies.isNotEmpty()) {
                        PhoneSectionTitle("Newly added movies")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(newlyMovies, key = { it.id }) { item ->
                                PhonePosterCard(
                                    item = item,
                                    progress = progressById[item.id],
                                    onClick = { detail = item }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (newlySeries.isNotEmpty()) {
                        PhoneSectionTitle("Newly added series")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(newlySeries, key = { it.id }) { item ->
                                PhonePosterCard(
                                    item = item,
                                    progress = progressById[item.id],
                                    onClick = { detail = item }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (liveSample.isNotEmpty()) {
                        PhoneSectionTitle("Live channels")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(liveSample, key = { it.id }) { item ->
                                PhonePosterCard(
                                    item = item,
                                    onClick = { onPlay(item) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        detail?.let { item ->
            PhoneDetailSheet(
                item = item,
                repository = repository,
                onDismiss = { detail = null },
                onPlay = onPlay,
                onPlayFromStart = onPlayFromStart,
                watchProgress = progressById[item.id] ?: continueWatching.find { it.id == item.id }
            )
        }
    }
}
