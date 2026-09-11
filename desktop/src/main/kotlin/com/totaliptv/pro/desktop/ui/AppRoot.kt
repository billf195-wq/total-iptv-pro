package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.desktop.data.Catalog
import com.totaliptv.pro.desktop.data.ContentKind
import com.totaliptv.pro.desktop.data.CatalogRepository
import com.totaliptv.pro.desktop.data.ChannelEpg
import com.totaliptv.pro.desktop.data.MediaItem
import com.totaliptv.pro.desktop.data.PreferencesStore
import com.totaliptv.pro.desktop.data.FavoritesStore
import com.totaliptv.pro.desktop.data.ResumeStore
import com.totaliptv.pro.desktop.data.SavedPrefs
import com.totaliptv.pro.desktop.data.SeriesDetail
import com.totaliptv.pro.desktop.data.VodDetail
import com.totaliptv.pro.desktop.player.StreamPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppRoot() {
    val scope = rememberCoroutineScope()
    val repo = remember { CatalogRepository() }

    var prefs by remember { mutableStateOf(PreferencesStore.load()) }
    var catalog by remember { mutableStateOf<Catalog?>(null) }
    var loading by remember { mutableStateOf(prefs.onboarded) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var playingTitle by remember { mutableStateOf<String?>(null) }
    var showOnboarding by remember { mutableStateOf(!prefs.onboarded) }

    var seriesDetail by remember { mutableStateOf<SeriesDetail?>(null) }
    var seriesLoading by remember { mutableStateOf(false) }
    var seriesError by remember { mutableStateOf<String?>(null) }

    var epgByStreamId by remember { mutableStateOf<Map<Int, ChannelEpg>>(emptyMap()) }
    var epgLoadingIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var resumeEntries by remember { mutableStateOf(ResumeStore.load()) }
    var favoriteEntries by remember { mutableStateOf(FavoritesStore.load()) }

    var vodDetail by remember { mutableStateOf<VodDetail?>(null) }
    var vodLoading by remember { mutableStateOf(false) }
    var vodError by remember { mutableStateOf<String?>(null) }

    fun persist(p: SavedPrefs) {
        PreferencesStore.save(p)
        prefs = PreferencesStore.load()
    }

    fun loadCatalog(p: SavedPrefs, fromRefresh: Boolean = false) {
        scope.launch {
            if (fromRefresh) {
                refreshing = true
                statusMessage = "Updating Live / Movies / Series…"
            } else {
                loading = true
            }
            error = null
            try {
                val cat = withContext(Dispatchers.IO) { repo.load(p) }
                // Keep appearance / player prefs; mark onboarded
                val merged = PreferencesStore.load().copy(
                    sourceType = p.sourceType,
                    m3uUrl = p.m3uUrl,
                    xtreamBaseUrl = p.xtreamBaseUrl,
                    xtreamUsername = p.xtreamUsername,
                    xtreamPassword = p.xtreamPassword,
                    onboarded = true
                )
                persist(merged)
                catalog = cat
                showOnboarding = false
                statusMessage = if (fromRefresh) {
                    "Catalog updated — Live ${cat.liveItems.size}, Movies ${cat.vodItems.size}, Series ${cat.seriesItems.size}"
                } else {
                    null
                }
                seriesDetail = null
                seriesError = null
                vodDetail = null
                vodError = null
                epgByStreamId = emptyMap()
                epgLoadingIds = emptySet()
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                statusMessage = null
                val stillOnboarded = PreferencesStore.load().onboarded
                if (!stillOnboarded && catalog == null) {
                    showOnboarding = true
                }
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    fun refreshFromSaved() {
        val saved = PreferencesStore.load()
        prefs = saved
        if (!saved.onboarded) {
            showOnboarding = true
            return
        }
        loadCatalog(saved, fromRefresh = true)
    }

    fun openSeries(item: MediaItem) {
        vodDetail = null
        vodError = null
        vodLoading = false
        scope.launch {
            seriesLoading = true
            seriesError = null
            seriesDetail = null
            try {
                val saved = PreferencesStore.load()
                prefs = saved
                val detail = withContext(Dispatchers.IO) { repo.loadSeriesDetail(saved, item) }
                seriesDetail = detail
            } catch (t: Throwable) {
                seriesError = t.message ?: t.javaClass.simpleName
                seriesDetail = null
            } finally {
                seriesLoading = false
            }
        }
    }

    fun openVod(item: MediaItem) {
        seriesDetail = null
        seriesError = null
        seriesLoading = false
        scope.launch {
            vodLoading = true
            vodError = null
            vodDetail = null
            try {
                val saved = PreferencesStore.load()
                prefs = saved
                val detail = withContext(Dispatchers.IO) { repo.loadVodDetail(saved, item) }
                vodDetail = detail
            } catch (t: Throwable) {
                vodError = t.message ?: t.javaClass.simpleName
                // Still show a minimal detail from the catalog item
                vodDetail = VodDetail(
                    streamId = item.xtreamStreamId ?: 0,
                    name = item.name,
                    plot = item.plot,
                    cast = item.cast,
                    rating = item.rating,
                    year = item.year,
                    genre = item.genre,
                    posterUrl = item.posterUrl ?: item.logoUrl,
                    backdropUrl = item.backdropUrl,
                    streamUrl = item.streamUrl,
                    catalogId = item.id,
                    categoryId = item.categoryId
                )
            } finally {
                vodLoading = false
            }
        }
    }

    fun toggleFavorite(item: MediaItem) {
        favoriteEntries = FavoritesStore.toggle(item)
    }

    fun playItem(item: MediaItem) {
        if (!item.playable || item.streamUrl.isBlank()) {
            openSeries(item)
            return
        }
        scope.launch {
            try {
                val playerPref = PreferencesStore.load().preferredPlayer
                withContext(Dispatchers.IO) {
                    // Persist continue-watching before launching external player
                    if (item.kind == ContentKind.VOD || item.kind == ContentKind.SERIES) {
                        resumeEntries = ResumeStore.recordPlay(item)
                    }
                    StreamPlayer.play(item.streamUrl, playerPref)
                }
                playingTitle = item.name
                error = null
            } catch (t: Throwable) {
                error = t.message
                playingTitle = null
            }
        }
    }

    fun resumeEntry(entry: ResumeStore.ResumeEntry, media: MediaItem?) {
        when (entry.kind) {
            ContentKind.VOD.name -> {
                val vod = media
                    ?: catalog?.vodItems?.find { it.id == entry.catalogId }
                    ?: catalog?.vodItems?.find { it.xtreamStreamId == entry.xtreamStreamId }
                if (vod != null) {
                    openVod(vod)
                } else if (entry.streamUrl.isNotBlank()) {
                    openVod(
                        MediaItem(
                            id = entry.catalogId,
                            name = entry.name,
                            streamUrl = entry.streamUrl,
                            categoryId = null,
                            kind = ContentKind.VOD,
                            posterUrl = entry.posterUrl,
                            xtreamStreamId = entry.xtreamStreamId,
                            playable = true
                        )
                    )
                }
            }
            ContentKind.SERIES.name -> {
                val series = media
                    ?: entry.seriesId?.let { sid -> catalog?.seriesItems?.find { it.xtreamStreamId == sid } }
                    ?: catalog?.seriesItems?.find { it.id == entry.catalogId }
                // Prefer replaying last episode URL when we still have it
                if (entry.streamUrl.isNotBlank()) {
                    playItem(
                        MediaItem(
                            id = entry.episodeId?.let { "ep-$it" } ?: "ep-resume",
                            name = entry.episodeLabel ?: entry.name,
                            streamUrl = entry.streamUrl,
                            categoryId = null,
                            kind = ContentKind.SERIES,
                            posterUrl = entry.posterUrl ?: series?.posterUrl,
                            playable = true,
                            parentSeriesId = entry.seriesId,
                            parentSeriesName = entry.name,
                            season = entry.season,
                            episodeNum = entry.episodeNum
                        )
                    )
                } else if (series != null) {
                    openSeries(series)
                }
            }
        }
    }

    fun needEpg(item: MediaItem) {
        val sid = item.xtreamStreamId ?: return
        if (sid in epgByStreamId || sid in epgLoadingIds) return
        scope.launch {
            epgLoadingIds = epgLoadingIds + sid
            try {
                val saved = PreferencesStore.load()
                val epg = withContext(Dispatchers.IO) { repo.loadChannelEpg(saved, sid) }
                epgByStreamId = epgByStreamId + (sid to epg)
            } catch (_: Throwable) {
                epgByStreamId = epgByStreamId + (sid to ChannelEpg(sid, emptyList()))
            } finally {
                epgLoadingIds = epgLoadingIds - sid
            }
        }
    }

    LaunchedEffect(Unit) {
        val saved = PreferencesStore.load()
        prefs = saved
        if (saved.onboarded) {
            showOnboarding = false
            loadCatalog(saved)
        } else {
            loading = false
            showOnboarding = true
        }
    }

    TipTheme(darkTheme = prefs.themeMode != "light") {
        when {
            showOnboarding -> {
                OnboardingScreen(
                    initial = prefs,
                    busy = loading,
                    error = error,
                    onConnect = { loadCatalog(it) }
                )
            }
            loading && catalog == null -> {
                Box(Modifier.fillMaxSize().background(TipBg), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = TipBlue)
                        Spacer(Modifier.height(16.dp))
                        Text("Loading catalog…", style = MaterialTheme.typography.bodyLarge, color = TipOnBg)
                        Text("Using saved login", style = MaterialTheme.typography.bodyMedium, color = TipMuted)
                    }
                }
            }
            catalog == null && error != null -> {
                Box(Modifier.fillMaxSize().background(TipBg), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text("Could not load catalog", style = MaterialTheme.typography.headlineMedium, color = TipOnBg)
                        Spacer(Modifier.height(8.dp))
                        Text(error ?: "", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { refreshFromSaved() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TipBlue,
                                contentColor = TipOnAmber
                            )
                        ) { Text("Update", color = TipOnAmber, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                        TextButton(onClick = {
                            StreamPlayer.stop()
                            playingTitle = null
                            catalog = null
                            persist(prefs.copy(onboarded = false))
                            showOnboarding = true
                            error = null
                        }) { Text("Change source") }
                    }
                }
            }
            catalog != null -> {
                BrowseScreen(
                    catalog = catalog!!,
                    prefs = prefs,
                    playingTitle = playingTitle,
                    refreshing = refreshing || loading,
                    statusMessage = statusMessage ?: error,
                    seriesDetail = seriesDetail,
                    seriesLoading = seriesLoading,
                    seriesError = seriesError,
                    vodDetail = vodDetail,
                    vodLoading = vodLoading,
                    vodError = vodError,
                    epgByStreamId = epgByStreamId,
                    epgLoadingIds = epgLoadingIds,
                    resumeEntries = resumeEntries,
                    favoriteEntries = favoriteEntries,
                    onPlay = { playItem(it) },
                    onOpenSeries = { openSeries(it) },
                    onCloseSeries = {
                        seriesDetail = null
                        seriesError = null
                        seriesLoading = false
                    },
                    onOpenVod = { openVod(it) },
                    onCloseVod = {
                        vodDetail = null
                        vodError = null
                        vodLoading = false
                    },
                    onToggleFavorite = { toggleFavorite(it) },
                    onStop = {
                        StreamPlayer.stop()
                        playingTitle = null
                    },
                    onRefresh = { refreshFromSaved() },
                    onLogout = {
                        StreamPlayer.stop()
                        playingTitle = null
                        catalog = null
                        seriesDetail = null
                        vodDetail = null
                        epgByStreamId = emptyMap()
                        persist(prefs.copy(onboarded = false))
                        showOnboarding = true
                        error = null
                        statusMessage = null
                    },
                    onSavePrefs = { persist(it) },
                    onNeedEpg = { needEpg(it) },
                    onResumeEntry = { entry, media -> resumeEntry(entry, media) }
                )
            }
            else -> {
                Box(Modifier.fillMaxSize().background(TipBg), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TipBlue)
                }
            }
        }
    }
}
