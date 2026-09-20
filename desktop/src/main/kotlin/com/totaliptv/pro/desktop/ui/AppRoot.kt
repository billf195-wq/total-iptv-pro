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
import com.totaliptv.pro.desktop.AppShutdown
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
import com.totaliptv.pro.desktop.data.SeriesEpisode
import com.totaliptv.pro.desktop.data.SeriesLaunch
import com.totaliptv.pro.desktop.data.SeriesPlayback
import com.totaliptv.pro.desktop.data.VodDetail
import com.totaliptv.pro.desktop.input.SeriesNextHotkeys
import com.totaliptv.pro.desktop.player.PlaybackDebugLog
import com.totaliptv.pro.desktop.player.StreamPlayer
import com.totaliptv.pro.desktop.update.AppUpdateManager
import com.totaliptv.pro.desktop.util.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Composable
fun AppRoot(seriesNextHost: SeriesNextHost? = null, onQuit: () -> Unit = {}) {
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

    var showSplash by remember { mutableStateOf(true) }
    val playSeq = remember { AtomicInteger(0) }
    var lastSeriesEpisodes by remember { mutableStateOf<List<SeriesEpisode>>(emptyList()) }
    var lastSeriesName by remember { mutableStateOf("") }
    var lastSeriesId by remember { mutableStateOf<Int?>(null) }
    val seriesSessionRef = remember { AtomicReference<ActiveSeriesPlay?>(null) }
    var seriesSession by remember { mutableStateOf<ActiveSeriesPlay?>(null) }

    fun setSeriesSession(session: ActiveSeriesPlay?) {
        seriesSessionRef.set(session)
        seriesSession = session
    }

    LaunchedEffect(Unit) {
        delay(SplashTiming.DURATION_MS)
        showSplash = false
    }

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
                lastSeriesEpisodes = detail.episodes
                lastSeriesName = detail.name
                lastSeriesId = detail.seriesId
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

    suspend fun resolveSeriesContext(item: MediaItem): SeriesPlayContext? {
        if (item.kind != ContentKind.SERIES) return null
        val sid = item.parentSeriesId ?: lastSeriesId
        var episodes = when {
            sid != null && seriesDetail?.seriesId == sid -> seriesDetail?.episodes.orEmpty()
            sid != null && lastSeriesId == sid -> lastSeriesEpisodes
            else -> emptyList()
        }
        var name = item.parentSeriesName ?: lastSeriesName
        var id = sid
        if (episodes.isEmpty() && sid != null) {
            val seriesItem = catalog?.seriesItems?.find { it.xtreamStreamId == sid }
                ?: MediaItem(
                    id = "series-$sid",
                    name = name.ifBlank { item.name },
                    streamUrl = "",
                    categoryId = null,
                    kind = ContentKind.SERIES,
                    posterUrl = item.posterUrl,
                    xtreamStreamId = sid,
                    playable = false
                )
            runCatching {
                val detail = withContext(Dispatchers.IO) {
                    repo.loadSeriesDetail(PreferencesStore.load(), seriesItem)
                }
                episodes = detail.episodes
                name = detail.name
                id = detail.seriesId
                lastSeriesEpisodes = detail.episodes
                lastSeriesName = detail.name
                lastSeriesId = detail.seriesId
            }
        }
        if (episodes.isEmpty()) return null
        return SeriesPlayContext(all = episodes, name = name, id = id)
    }

    fun playItem(item: MediaItem, reason: String = "play") {
        if (!item.playable || item.streamUrl.isBlank()) {
            openSeries(item)
            return
        }
        val seq = playSeq.incrementAndGet()
        scope.launch {
            try {
                val playerPref = PreferencesStore.load().preferredPlayer
                val ctx = resolveSeriesContext(item)
                val windows = AppPaths.isWindows
                val plan = if (ctx != null) {
                    lastSeriesEpisodes = ctx.all
                    lastSeriesName = ctx.name
                    lastSeriesId = ctx.id
                    SeriesLaunch.plan(
                        item = item,
                        episodes = ctx.all,
                        seriesName = ctx.name,
                        seriesId = ctx.id,
                        windowsSingleUrl = windows
                    )
                } else {
                    SeriesLaunch.single(item)
                }
                var startWithSeries = plan.start
                if (startWithSeries.kind == ContentKind.SERIES && startWithSeries.parentSeriesId == null && plan.seriesId != null) {
                    startWithSeries = startWithSeries.copy(
                        parentSeriesId = plan.seriesId,
                        parentSeriesName = plan.seriesName.ifBlank { startWithSeries.parentSeriesName.orEmpty() }
                    )
                }
                if (startWithSeries.kind == ContentKind.SERIES) {
                    val episodes = plan.allEpisodes.ifEmpty { ctx?.all.orEmpty() }
                    val current = plan.currentEpisode ?: SeriesLaunch.placeholderCurrent(startWithSeries)
                    if (episodes.isNotEmpty() || current.streamUrl.isNotBlank()) {
                        setSeriesSession(
                            ActiveSeriesPlay(
                                episodes = episodes,
                                seriesName = plan.seriesName.ifBlank { lastSeriesName },
                                seriesId = plan.seriesId ?: lastSeriesId,
                                current = current
                            )
                        )
                    } else {
                        setSeriesSession(null)
                    }
                } else {
                    setSeriesSession(null)
                }
                if (startWithSeries.kind == ContentKind.VOD || startWithSeries.kind == ContentKind.SERIES) {
                    val recorded = withContext(Dispatchers.IO) { ResumeStore.recordPlay(startWithSeries) }
                    resumeEntries = recorded
                }
                val urls = plan.urls
                if (urls.isEmpty()) error("No stream URL for ${item.name}")
                val binary = withContext(Dispatchers.IO) {
                    StreamPlayer.playQueue(urls, playerPref)
                }
                PlaybackDebugLog.record(
                    episodeId = plan.currentEpisode?.id ?: startWithSeries.id,
                    season = startWithSeries.season ?: plan.currentEpisode?.season,
                    episodeNum = startWithSeries.episodeNum ?: plan.currentEpisode?.episodeNum,
                    streamUrl = urls.first(),
                    playerBinary = binary,
                    windows = windows,
                    playlist = StreamPlayer.lastLaunchWasPlaylist,
                    reason = reason
                )
                playingTitle = startWithSeries.name
                error = null
                val playlist = StreamPlayer.lastLaunchWasPlaylist
                launch(Dispatchers.IO) {
                    val naturalEnd = StreamPlayer.waitForExit()
                    if (seq != playSeq.get()) return@launch
                    if (!naturalEnd) {
                        withContext(Dispatchers.Main) {
                            if (seq == playSeq.get()) {
                                playingTitle = null
                                setSeriesSession(null)
                            }
                        }
                        return@launch
                    }
                    // Linux VLC/mpv playlist already walked the remaining queue.
                    if (playlist) {
                        val lastEp = plan.allEpisodes.lastOrNull()
                        val last = lastEp?.toMediaItem(plan.seriesName, plan.seriesId)
                        if (last != null && last.kind == ContentKind.SERIES && last.parentSeriesId != null) {
                            val recorded = ResumeStore.recordPlay(last)
                            withContext(Dispatchers.Main) {
                                if (seq == playSeq.get()) {
                                    resumeEntries = recorded
                                    playingTitle = null
                                    setSeriesSession(null)
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                if (seq == playSeq.get()) {
                                    playingTitle = null
                                    setSeriesSession(null)
                                }
                            }
                        }
                        return@launch
                    }
                    // Windows VLC and ffplay: one URL per process — start SxxE(n+1) ourselves.
                    val next = plan.nextEpisode?.toMediaItem(plan.seriesName, plan.seriesId)
                        ?: SeriesPlayback.nextAfterPlaying(
                            ctx?.all.orEmpty(),
                            startWithSeries.season,
                            startWithSeries.episodeNum,
                            startWithSeries.id,
                            startWithSeries.streamUrl
                        )?.toMediaItem(ctx?.name ?: plan.seriesName, ctx?.id ?: plan.seriesId)
                    if (next != null && next.streamUrl.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            if (seq == playSeq.get()) playItem(next, reason = "auto-advance")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            if (seq == playSeq.get()) {
                                playingTitle = null
                                setSeriesSession(null)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                error = t.message
                playingTitle = null
                setSeriesSession(null)
            }
        }
    }

    fun skipToNextEpisode() {
        val session = seriesSessionRef.get()
        val episodes = session?.episodes?.ifEmpty { lastSeriesEpisodes } ?: lastSeriesEpisodes
        val next = session?.next
            ?: SeriesPlayback.nextAfterPlaying(
                episodes,
                session?.current?.season,
                session?.current?.episodeNum,
                session?.current?.id,
                session?.current?.streamUrl
            )
        if (next == null || next.streamUrl.isBlank()) {
            val (eps, idx) = SeriesPlayback.indexInSeries(
                episodes,
                session?.current?.season,
                session?.current?.episodeNum,
                session?.current?.id,
                session?.current?.streamUrl
            )
            PlaybackDebugLog.record(
                episodeId = session?.current?.id,
                season = session?.current?.season,
                episodeNum = session?.current?.episodeNum,
                streamUrl = session?.current?.streamUrl.orEmpty(),
                playerBinary = "-",
                windows = AppPaths.isWindows,
                playlist = StreamPlayer.lastLaunchWasPlaylist,
                reason = "skip-no-next",
                episodeCount = eps,
                episodeIndex = idx
            )
            statusMessage = SeriesPlayback.LAST_EPISODE_MESSAGE
            return
        }
        val name = session?.seriesName ?: lastSeriesName
        val id = session?.seriesId ?: lastSeriesId
        playItem(next.toMediaItem(name, id), reason = "skip")
    }

    fun stopPlayback() {
        playSeq.incrementAndGet()
        StreamPlayer.stop()
        playingTitle = null
        setSeriesSession(null)
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
                    lastSeriesId = entry.seriesId ?: lastSeriesId
                    lastSeriesName = entry.name
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
        // Quiet update check so GTR / Bigboybill see a banner when a shelf package is ready.
        launch(Dispatchers.IO) {
            val shelf = PreferencesStore.load().updateShelfUrl
            val result = AppUpdateManager().check(shelf)
            if (result.phase == com.totaliptv.pro.desktop.update.UpdatePhase.Available) {
                withContext(Dispatchers.Main) {
                    if (statusMessage.isNullOrBlank()) {
                        statusMessage = result.message + " — open Settings to install"
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val handle = SeriesNextHotkeys.addListener { skipToNextEpisode() }
        onDispose { handle.close() }
    }

    SideEffect {
        if (AppShutdown.isExiting()) return@SideEffect
        val host = seriesNextHost
        if (host != null) {
            host.onNext = { skipToNextEpisode() }
            host.onStop = { stopPlayback() }
            host.session = seriesSession
            host.darkTheme = prefs.themeMode != "light"
        }
    }

    TipTheme(darkTheme = prefs.themeMode != "light") {
        if (seriesNextHost == null) {
            val overlay = seriesSession
            if (overlay != null) {
                SeriesNextOverlay(
                    session = overlay,
                    darkTheme = prefs.themeMode != "light",
                    onNext = { skipToNextEpisode() },
                    onStop = { stopPlayback() }
                )
            }
        }
        if (showSplash) {
            SplashScreen()
            return@TipTheme
        }
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
                            stopPlayback()
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
                    playingSeriesId = seriesSession?.seriesId,
                    playingSeason = seriesSession?.current?.season,
                    playingEpisodeNum = seriesSession?.current?.episodeNum,
                    playingEpisodeId = seriesSession?.current?.id,
                    playingStreamUrl = seriesSession?.current?.streamUrl,
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
                    onStop = { stopPlayback() },
                    onQuit = onQuit,
                    onRefresh = { refreshFromSaved() },
                    onLogout = {
                        stopPlayback()
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

private data class SeriesPlayContext(
    val all: List<SeriesEpisode>,
    val name: String,
    val id: Int?
)
