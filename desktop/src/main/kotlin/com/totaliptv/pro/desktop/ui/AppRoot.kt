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
import com.totaliptv.pro.desktop.data.GuideKeys
import com.totaliptv.pro.desktop.data.ResumeStore
import com.totaliptv.pro.desktop.data.SavedPrefs
import com.totaliptv.pro.desktop.data.SeriesDetail
import com.totaliptv.pro.desktop.data.SeriesEpisode
import com.totaliptv.pro.desktop.data.SeriesAdvance
import com.totaliptv.pro.desktop.data.SeriesLaunch
import com.totaliptv.pro.desktop.data.SeriesPlayback
import com.totaliptv.pro.desktop.data.VodDetail
import com.totaliptv.pro.desktop.input.SeriesNextHotkeys
import com.totaliptv.pro.desktop.data.LastEpisodeBanner
import com.totaliptv.pro.desktop.dvr.DvrKind
import com.totaliptv.pro.desktop.dvr.DvrRecordUi
import com.totaliptv.pro.desktop.dvr.DvrRecorder
import com.totaliptv.pro.desktop.dvr.DvrStartReason
import com.totaliptv.pro.desktop.dvr.RecordingEntry
import com.totaliptv.pro.desktop.dvr.ScheduledRecording
import com.totaliptv.pro.desktop.player.PlaybackAdvance
import com.totaliptv.pro.desktop.player.PlaybackDebugLog
import com.totaliptv.pro.desktop.player.SplitSession
import com.totaliptv.pro.desktop.player.SplitSide
import com.totaliptv.pro.desktop.player.StreamPlayer
import com.totaliptv.pro.desktop.update.AppUpdateManager
import com.totaliptv.pro.desktop.util.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    var playingItem by remember { mutableStateOf<MediaItem?>(null) }
    var showOnboarding by remember { mutableStateOf(!prefs.onboarded) }

    var seriesDetail by remember { mutableStateOf<SeriesDetail?>(null) }
    var seriesLoading by remember { mutableStateOf(false) }
    var seriesError by remember { mutableStateOf<String?>(null) }

    var epgByStreamId by remember { mutableStateOf<Map<Int, ChannelEpg>>(emptyMap()) }
    var epgLoadingIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var resumeEntries by remember { mutableStateOf(ResumeStore.load()) }
    var splitSession by remember { mutableStateOf<SplitSession?>(null) }
    var splitDialogOpen by remember { mutableStateOf(false) }
    var splitDialogInitial by remember { mutableStateOf<MediaItem?>(null) }
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
    var dvrTick by remember { mutableStateOf(0) }
    val dvrSnapshot = remember(dvrTick, prefs.recordingsDir) { DvrRecorder.snapshot() }

    fun setSeriesSession(session: ActiveSeriesPlay?) {
        if (session != null && AppShutdown.isExiting()) return
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
        dvrTick++
    }

    fun refreshDvr() {
        dvrTick++
        DvrRecorder.lastMessage?.let { statusMessage = it }
    }

    fun recordNow(item: MediaItem, title: String? = null, endMs: Long? = null) {
        if (item.streamUrl.isBlank()) {
            statusMessage = "No stream URL to record"
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    DvrRecorder.startNow(
                        channelName = item.parentSeriesName?.takeIf { it.isNotBlank() } ?: item.name,
                        title = title?.takeIf { it.isNotBlank() } ?: item.name,
                        streamUrl = item.streamUrl,
                        channelId = item.id,
                        scheduledEndMs = endMs,
                        contentKind = item.kind.name,
                        reason = DvrStartReason.USER_RECORD
                    )
                }
                refreshDvr()
            } catch (t: Throwable) {
                statusMessage = t.message ?: "Could not start recording"
            }
        }
    }

    fun stopRecording() {
        scope.launch {
            withContext(Dispatchers.IO) { DvrRecorder.stop() }
            delay(400)
            refreshDvr()
        }
    }

    fun scheduleProgram(item: MediaItem, title: String, startMs: Long, endMs: Long) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    DvrRecorder.schedule(
                        channelName = item.parentSeriesName?.takeIf { it.isNotBlank() } ?: item.name,
                        title = title,
                        streamUrl = item.streamUrl,
                        startMs = startMs,
                        endMs = endMs,
                        channelId = item.id,
                        contentKind = item.kind.name
                    )
                }
                refreshDvr()
            } catch (t: Throwable) {
                statusMessage = t.message ?: "Could not schedule"
            }
        }
    }

    fun deleteRecording(entry: RecordingEntry) {
        scope.launch {
            withContext(Dispatchers.IO) { DvrRecorder.deleteRecording(entry.id) }
            refreshDvr()
        }
    }

    fun cancelSchedule(item: ScheduledRecording) {
        scope.launch {
            withContext(Dispatchers.IO) { DvrRecorder.cancelSchedule(item.id) }
            refreshDvr()
        }
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

    fun playItem(item: MediaItem, reason: String = "play", startPositionSeconds: Long? = null) {
        if (AppShutdown.isExiting()) return
        if (!item.playable || item.streamUrl.isBlank()) {
            openSeries(item)
            return
        }
        val seq = playSeq.incrementAndGet()
        scope.launch {
            try {
                val savedPrefs = PreferencesStore.load()
                val playerPref = savedPrefs.preferredPlayer
                val openFullscreen = savedPrefs.openPlayerFullscreen
                val ctx = resolveSeriesContext(item)
                val windows = AppPaths.isWindows
                val cachedEpisodes = lastSeriesEpisodes
                val sameCachedSeries = item.kind == ContentKind.SERIES &&
                    cachedEpisodes.isNotEmpty() &&
                    (item.parentSeriesId == null || item.parentSeriesId == lastSeriesId)
                val plan = if (ctx != null) {
                    lastSeriesEpisodes = ctx.all
                    lastSeriesName = ctx.name
                    lastSeriesId = ctx.id
                    SeriesLaunch.plan(
                        item = item,
                        episodes = ctx.all,
                        seriesName = ctx.name,
                        seriesId = ctx.id
                    )
                } else if (sameCachedSeries) {
                    SeriesLaunch.plan(
                        item = item,
                        episodes = cachedEpisodes,
                        seriesName = item.parentSeriesName ?: lastSeriesName,
                        seriesId = item.parentSeriesId ?: lastSeriesId
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
                val live = startWithSeries.kind == ContentKind.LIVE ||
                    StreamPlayer.isLiveStreamUrl(urls.first())
                val progressKey = if (live) null else ResumeStore.progressKeyFor(startWithSeries)
                val binary = withContext(Dispatchers.IO) {
                    StreamPlayer.playQueue(
                        urls,
                        playerPref,
                        live = live,
                        startPositionSeconds = if (live) null else startPositionSeconds,
                        fullscreen = openFullscreen,
                        scope = scope,
                        onProgress = if (progressKey == null) {
                            null
                        } else {
                            { pos, dur, pct ->
                                val updated = ResumeStore.updateProgress(progressKey, pos, dur, pct)
                                scope.launch(Dispatchers.Main) {
                                    if (seq == playSeq.get()) resumeEntries = updated
                                }
                            }
                        }
                    )
                }
                splitSession = null
                val launchedAtMs = StreamPlayer.lastLaunchAtMs
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
                playingItem = startWithSeries
                error = null
                val playlist = StreamPlayer.lastLaunchWasPlaylist
                launch(Dispatchers.IO) {
                    val naturalEnd = StreamPlayer.waitForExit()
                    if (seq != playSeq.get()) return@launch
                    if (!naturalEnd) {
                        withContext(Dispatchers.Main) {
                            if (seq == playSeq.get()) {
                                playingTitle = null
                                playingItem = null
                                setSeriesSession(null)
                            }
                        }
                        return@launch
                    }
                    // Playlist launches (legacy Linux M3U) already walked the remaining queue.
                    // 1.2.8+ Linux matches Windows: one URL, so this branch should not run.
                    if (playlist) {
                        val lastEp = plan.allEpisodes.lastOrNull()
                        val last = lastEp?.toMediaItem(plan.seriesName, plan.seriesId)
                        if (last != null && last.kind == ContentKind.SERIES && last.parentSeriesId != null) {
                            val recorded = ResumeStore.recordPlay(last)
                            withContext(Dispatchers.Main) {
                                if (seq == playSeq.get()) {
                                    resumeEntries = recorded
                                    playingTitle = null
                                    playingItem = null
                                    setSeriesSession(null)
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                if (seq == playSeq.get()) {
                                    playingTitle = null
                                    playingItem = null
                                    setSeriesSession(null)
                                }
                            }
                        }
                        return@launch
                    }
                    // Live: player should stay up; never sequential-next a channel.
                    // VOD: one URL per process. Do not treat a 5–15s VLC crash /
                    // one-instance handoff as EOF, and never relaunch the same
                    // episodeId/url (GTR same-episode loop).
                    val durationMs = StreamPlayer.lastPlaybackDurationMs.takeIf { it > 0 }
                        ?: (System.currentTimeMillis() - launchedAtMs).coerceAtLeast(0L)
                    val exitCode = StreamPlayer.lastExitCode
                    if (live) {
                        PlaybackDebugLog.record(
                            episodeId = plan.currentEpisode?.id ?: startWithSeries.id,
                            season = startWithSeries.season ?: plan.currentEpisode?.season,
                            episodeNum = startWithSeries.episodeNum ?: plan.currentEpisode?.episodeNum,
                            streamUrl = startWithSeries.streamUrl,
                            playerBinary = binary,
                            windows = windows,
                            playlist = playlist,
                            reason = PlaybackAdvance.REASON_SKIPPED_LIVE,
                            durationMs = durationMs,
                            exitCode = exitCode
                        )
                        withContext(Dispatchers.Main) {
                            if (seq == playSeq.get()) {
                                playingTitle = null
                                playingItem = null
                            }
                        }
                        return@launch
                    }
                    val sessionNow = seriesSessionRef.get()
                    val episodesNow = sessionNow?.episodes?.ifEmpty { lastSeriesEpisodes }
                        ?: lastSeriesEpisodes.ifEmpty { plan.allEpisodes }.ifEmpty { ctx?.all.orEmpty() }
                    val outcome = SeriesAdvance.afterNaturalEnd(
                        start = startWithSeries,
                        plan = plan,
                        episodes = episodesNow,
                        seriesName = plan.seriesName.ifBlank { sessionNow?.seriesName ?: lastSeriesName },
                        seriesId = plan.seriesId ?: sessionNow?.seriesId ?: lastSeriesId,
                        durationMs = durationMs,
                        exitCode = exitCode
                    )
                    PlaybackDebugLog.record(
                        episodeId = plan.currentEpisode?.id ?: startWithSeries.id,
                        season = startWithSeries.season ?: plan.currentEpisode?.season,
                        episodeNum = startWithSeries.episodeNum ?: plan.currentEpisode?.episodeNum,
                        streamUrl = startWithSeries.streamUrl,
                        playerBinary = binary,
                        windows = windows,
                        playlist = playlist,
                        reason = when (outcome) {
                            is SeriesAdvance.Outcome.PlayNext -> outcome.reason
                            is SeriesAdvance.Outcome.Stop -> outcome.reason
                        },
                        durationMs = durationMs,
                        exitCode = exitCode
                    )
                    when (outcome) {
                        is SeriesAdvance.Outcome.PlayNext -> {
                            withContext(Dispatchers.Main) {
                                if (seq == playSeq.get() && !AppShutdown.isExiting()) {
                                    playItem(outcome.item, reason = outcome.reason)
                                }
                            }
                        }
                        is SeriesAdvance.Outcome.Stop -> {
                            withContext(Dispatchers.Main) {
                                if (seq == playSeq.get()) {
                                    playingTitle = null
                                    playingItem = null
                                    if (outcome.reason == PlaybackAdvance.REASON_SAME_URL ||
                                        outcome.reason == PlaybackAdvance.REASON_NO_NEXT
                                    ) {
                                        setSeriesSession(null)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                error = t.message
                playingTitle = null
                playingItem = null
                setSeriesSession(null)
            }
        }
    }

    fun skipToNextEpisode() {
        if (AppShutdown.isExiting()) return
        val session = seriesSessionRef.get()
        val episodes = session?.episodes?.ifEmpty { lastSeriesEpisodes } ?: lastSeriesEpisodes
        val name = session?.seriesName ?: lastSeriesName
        val id = session?.seriesId ?: lastSeriesId
        val outcome = SeriesAdvance.afterSkip(
            current = session?.current,
            plannedNext = session?.next,
            episodes = episodes,
            seriesName = name,
            seriesId = id
        )
        when (outcome) {
            is SeriesAdvance.Outcome.PlayNext -> {
                playItem(outcome.item, reason = outcome.reason)
            }
            is SeriesAdvance.Outcome.Stop -> {
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
                    reason = outcome.reason,
                    episodeCount = eps,
                    episodeIndex = idx
                )
                if (outcome.reason == "skip-no-next") {
                    statusMessage = SeriesPlayback.LAST_EPISODE_MESSAGE
                    scope.launch {
                        delay(LastEpisodeBanner.AUTO_DISMISS_MS)
                        if (statusMessage == SeriesPlayback.LAST_EPISODE_MESSAGE) {
                            statusMessage = null
                        }
                    }
                }
            }
        }
    }

    fun stopPlayback() {
        playSeq.incrementAndGet()
        StreamPlayer.stop()
        playingTitle = null
        playingItem = null
        splitSession = null
        setSeriesSession(null)
    }

    LaunchedEffect(splitSession != null) {
        if (splitSession == null) return@LaunchedEffect
        // The player mutates one session object. Remember the side we last
        // showed, or a click in VLC never changes the highlighted button.
        var displayed = splitSession?.activeAudio
        while (isActive) {
            val playerSession = StreamPlayer.splitSession
            if (playerSession == null) {
                stopPlayback()
                break
            }
            val live = playerSession.activeAudio
            if (live != displayed) {
                displayed = live
                val current = splitSession ?: break
                splitSession = current.copy(activeAudio = live)
            }
            delay(250)
        }
    }

    fun playSplit(left: MediaItem, right: MediaItem) {
        scope.launch {
            try {
                val playerPref = PreferencesStore.load().preferredPlayer
                val session = withContext(Dispatchers.IO) {
                    StreamPlayer.playSplitScreen(scope, left, right, playerPref)
                }
                splitSession = session
                playingTitle = "${left.name}  |  ${right.name}"
                playingItem = null
                error = null
            } catch (t: Throwable) {
                error = t.message ?: StreamPlayer.SPLIT_NEEDS_VLC
                splitSession = null
            }
        }
    }

    fun switchSplitAudio(side: SplitSide) {
        StreamPlayer.switchSplitAudio(side)
        val current = splitSession
        splitSession = if (current != null) current.copy(activeAudio = side) else StreamPlayer.splitSession
    }

    fun playRecording(entry: RecordingEntry) {
        if (entry.filePath.isBlank()) {
            statusMessage = "Recording file missing"
            return
        }
        playItem(
            MediaItem(
                id = "rec-${entry.id}",
                name = entry.title,
                streamUrl = entry.filePath,
                categoryId = null,
                kind = when (DvrKind.normalize(entry.contentKind)) {
                    DvrKind.SERIES -> ContentKind.SERIES
                    else -> ContentKind.VOD
                },
                playable = true
            )
        )
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
                    val progress = ResumeStore.progressForEpisode(entry.seriesId, entry.episodeId, resumeEntries)
                    val seconds = progress?.takeIf { it.hasProgress }?.positionMs?.div(1000)?.takeIf { it > 0 }
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
                            episodeNum = entry.episodeNum,
                            xtreamStreamId = entry.xtreamStreamId
                        ),
                        startPositionSeconds = seconds
                    )
                } else if (series != null) {
                    openSeries(series)
                }
            }
        }
    }

    fun needEpg(item: MediaItem) {
        val sid = GuideKeys.of(item) ?: return
        if (sid in epgByStreamId || sid in epgLoadingIds) return
        scope.launch {
            epgLoadingIds = epgLoadingIds + sid
            try {
                val saved = PreferencesStore.load()
                val epg = withContext(Dispatchers.IO) { repo.loadChannelEpg(saved, item) }
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
        DvrRecorder.ensureScheduler()
        launch {
            while (true) {
                delay(3_000)
                dvrTick++
            }
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
        onDispose {
            handle.close()
            seriesNextHost?.disposeOverlay()
        }
    }

    LaunchedEffect(seriesSession) {
        val session = seriesSession ?: return@LaunchedEffect
        val mode = LastEpisodeBanner.overlayMode(
            session.episodes,
            session.current.season,
            session.current.episodeNum,
            session.current.id,
            session.current.streamUrl
        )
        if (mode != LastEpisodeBanner.Mode.LAST_BRIEF) return@LaunchedEffect
        val key = "${session.seriesId}|${session.current.id}|${session.current.streamUrl}"
        delay(LastEpisodeBanner.AUTO_DISMISS_MS)
        seriesNextHost?.dismissLastIfMatching(key)
    }

    SideEffect {
        val host = seriesNextHost ?: return@SideEffect
        if (AppShutdown.isExiting()) {
            host.disposeOverlay()
            return@SideEffect
        }
        val play = playingItem
        host.sync(
            session = seriesSession,
            darkTheme = prefs.themeMode != "light",
            onNext = { skipToNextEpisode() },
            onStop = { stopPlayback() },
            onRecord = {
                val item = playingItem
                if (item != null && item.streamUrl.isNotBlank()) {
                    recordNow(item, item.name, null)
                }
            },
            recordingThisItem = DvrRecordUi.matches(dvrSnapshot.active, play?.id, play?.streamUrl),
            nowMs = System.currentTimeMillis()
        )
    }

    TipTheme(darkTheme = prefs.themeMode != "light") {
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
                    playingItem = playingItem,
                    recordingTitle = dvrSnapshot.active?.let { "REC ${it.channelName}" },
                    dvrSnapshot = dvrSnapshot,
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
                    onPlayAt = { media, seconds -> playItem(media, startPositionSeconds = seconds) },
                    splitActive = splitSession != null,
                    splitAudioLeft = splitSession?.activeAudio != SplitSide.RIGHT,
                    onOpenGameDay = { initial ->
                        splitDialogInitial = initial
                        splitDialogOpen = true
                    },
                    onSplitAudioLeft = { left ->
                        switchSplitAudio(if (left) SplitSide.LEFT else SplitSide.RIGHT)
                    },
                    onStopSplit = { stopPlayback() },
                    onOpenSeries = { openSeries(it) },
                    onCloseSeries = {
                        if (splitSession != null) stopPlayback()
                        seriesDetail = null
                        seriesError = null
                        seriesLoading = false
                    },
                    onOpenVod = { openVod(it) },
                    onCloseVod = {
                        if (splitSession != null) stopPlayback()
                        vodDetail = null
                        vodError = null
                        vodLoading = false
                    },
                    onToggleFavorite = { toggleFavorite(it) },
                    onStop = { stopPlayback() },
                    onQuit = {
                        playSeq.incrementAndGet()
                        onQuit()
                    },
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
                    onResumeEntry = { entry, media -> resumeEntry(entry, media) },
                    onRecordNow = { item, title, endMs -> recordNow(item, title, endMs) },
                    onStopRecording = { stopRecording() },
                    onScheduleProgram = { item, title, start, end -> scheduleProgram(item, title, start, end) },
                    onPlayRecording = { playRecording(it) },
                    onDeleteRecording = { deleteRecording(it) },
                    onCancelSchedule = { cancelSchedule(it) }
                )
                if (splitDialogOpen) {
                    SplitScreenDialog(
                        liveChannels = catalog!!.liveItems,
                        initialLeft = splitDialogInitial,
                        onDismiss = {
                            splitDialogOpen = false
                            splitDialogInitial = null
                        },
                        onLaunch = { left, right ->
                            splitDialogOpen = false
                            splitDialogInitial = null
                            playSplit(left, right)
                        }
                    )
                }
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
