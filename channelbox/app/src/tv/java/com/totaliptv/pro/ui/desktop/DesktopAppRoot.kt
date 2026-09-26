package com.totaliptv.pro.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.FavoriteRef
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.model.WatchProgress
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.artwork.RankPace
import com.totaliptv.pro.artwork.TvRatings
import com.totaliptv.pro.ui.components.MovieDetailSheet
import com.totaliptv.pro.ui.epg.EpgGuideScreen
import com.totaliptv.pro.ui.theme.AccentPreset
import com.totaliptv.pro.ui.theme.AppearanceMode
import com.totaliptv.pro.ui.theme.TotalIptvProTheme
import com.totaliptv.pro.ui.onboarding.OnboardingScreen
import android.widget.Toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch

enum class DesktopNavSection { HOME, LIVE, MOVIES, SERIES, GUIDE, FAVORITES, RECORDINGS, SETTINGS }

@Composable
fun DesktopAppRoot(
    repository: CatalogRepository,
    onPlay: (MediaItem) -> Unit,
    onPlayFromStart: (MediaItem) -> Unit,
    onPlayFavorite: (FavoriteRef) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as? TotalIptvProApp
    val appearance by (app?.preferences?.appearanceMode ?: kotlinx.coroutines.flow.flowOf(AppearanceMode.DARK))
        .collectAsState(initial = AppearanceMode.DARK)
    val accent by (app?.preferences?.accentPreset ?: kotlinx.coroutines.flow.flowOf(AccentPreset.AMBER))
        .collectAsState(initial = AccentPreset.AMBER)
    val posterColumnsPref by (app?.preferences?.posterColumns ?: kotlinx.coroutines.flow.flowOf(6))
        .collectAsState(initial = 6)
    DesktopTipTheme(appearance = appearance, accent = accent) {
        val sources by repository.sources.collectAsState(initial = emptyList())
        val revision by repository.catalogRevision.collectAsState(initial = 0)
        val vodLoading by repository.vodLoading.collectAsState(initial = false)
        var ready by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(true) }
        var refreshing by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var showOnboarding by remember { mutableStateOf(false) }
        var section by remember { mutableStateOf(DesktopNavSection.HOME) }
        var search by remember { mutableStateOf("") }
        var categoryId by remember { mutableStateOf<String?>(null) }
        var browseSort by remember { mutableStateOf("AZ") }
        val posterColumns = posterColumnsPref
        var statusMessage by remember { mutableStateOf<String?>(null) }
        var detailItem by remember { mutableStateOf<MediaItem?>(null) }
        var restoreFocusId by remember { mutableStateOf<String?>(null) }
        var restoreFocusIndex by remember { mutableIntStateOf(-1) }
        var restoreFocusScope by remember { mutableStateOf<String?>(null) }
        var pendingFocusRestore by remember { mutableStateOf(false) }
        val posterFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
        fun posterFocus(scope: String, id: String): FocusRequester =
            posterFocusRequesters.getOrPut("$scope:$id") { FocusRequester() }
        var favorites by remember { mutableStateOf<List<FavoriteRef>>(emptyList()) }
        val scope = rememberCoroutineScope()
        val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }

        fun snapshotMovies() = runCatching { repository.itemsByKind(ContentKind.VOD) }.getOrDefault(emptyList())
        fun snapshotSeries() = runCatching { repository.itemsByKind(ContentKind.SERIES) }.getOrDefault(emptyList())

        fun openDetailOrPlay(
            item: MediaItem,
            focusKey: String = item.id,
            index: Int = -1,
            scope: String = "desk-grid"
        ) {
            when (item.kind) {
                ContentKind.VOD, ContentKind.SERIES -> {
                    restoreFocusId = focusKey
                    restoreFocusIndex = index
                    restoreFocusScope = scope
                    pendingFocusRestore = false
                    detailItem = item
                }
                else -> onPlay(item)
            }
        }

        fun dismissDetail(restore: Boolean) {
            detailItem = null
            pendingFocusRestore = restore && restoreFocusId != null
        }

        fun consumeFocusRestore() {
            pendingFocusRestore = false
        }

        // Hard cap: never leave pendingFocusRestore true (sidebar stays unfocusable while pending).
        LaunchedEffect(pendingFocusRestore) {
            if (!pendingFocusRestore) return@LaunchedEffect
            kotlinx.coroutines.delay(2000)
            pendingFocusRestore = false
        }

        fun toggleFavoriteToast(item: MediaItem) {
            val wasFav = item.id in favoriteIds
            scope.launch {
                runCatching { repository.toggleFavorite(item) }
                Toast.makeText(
                    context,
                    if (wasFav) "Removed from favorites" else "Added to favorites",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        LaunchedEffect(Unit) {
            repository.favorites.collectLatest { favorites = it }
        }

        LaunchedEffect(sources) {
            if (sources.isEmpty()) {
                showOnboarding = true
                ready = true
                loading = false
                return@LaunchedEffect
            }
            showOnboarding = false
            // After Classic <-> Desktop recreate the Application (and catalog cache) survive.
            // Skip a full-screen blank "Loading catalog" frame when data is already warm.
            val warm = runCatching {
                repository.liveItems(1).isNotEmpty() ||
                    snapshotMovies().isNotEmpty() ||
                    snapshotSeries().isNotEmpty()
            }.getOrDefault(false)
            if (!warm) loading = true
            error = null
            runCatching { repository.ensureCatalogLoaded(force = false) }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            ready = true
            loading = false
        }

        // Keep resume fresh when entering Home — never sync-scan on Movies→Home first frame.
        var resume by remember { mutableStateOf<List<WatchProgress>>(emptyList()) }
        LaunchedEffect(revision, section, app) {
            if (section != DesktopNavSection.HOME) return@LaunchedEffect
            yield()
            resume = withContext(Dispatchers.IO) {
                runCatching { app?.watchProgress?.continueWatching(24).orEmpty() }.getOrDefault(emptyList())
            }
        }

        when {
            !ready || (loading && !showOnboarding) -> {
                Box(Modifier.fillMaxSize().background(TipBg), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = TipAmber)
                        Spacer(Modifier.height(TipDimens.dp(16)))
                        Text("Loading catalog…", color = TipGoldText)
                    }
                }
            }
            showOnboarding -> {
                // Reuse classic onboarding (TV material) inside desktop shell background
                Box(Modifier.fillMaxSize().background(TipBg)) {
                    OnboardingScreen(
                        repository = repository,
                        onDone = {
                            showOnboarding = false
                            scope.launch {
                                loading = true
                                runCatching { repository.ensureCatalogLoaded(force = true) }
                                loading = false
                            }
                        }
                    )
                }
            }
            error != null && runCatching { repository.liveItems(1) }.getOrDefault(emptyList()).isEmpty() && snapshotMovies().isEmpty() -> {
                Box(Modifier.fillMaxSize().background(TipBg), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(TipDimens.dp(24))) {
                        Text("Could not load catalog", color = TipGoldText, fontSize = TipDimens.sp(22), fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(TipDimens.dp(8)))
                        Text(error ?: "", color = androidx.compose.ui.graphics.Color(0xFFFF8A80))
                        Spacer(Modifier.height(TipDimens.dp(16)))
                        AmberButton("Update", onClick = {
                            scope.launch {
                                refreshing = true
                                runCatching { repository.ensureCatalogLoaded(force = true) }
                                    .onFailure { error = it.message }
                                    .onSuccess { error = null }
                                refreshing = false
                            }
                        })
                        Spacer(Modifier.height(TipDimens.dp(8)))
                        AmberButton("Change source", onClick = { showOnboarding = true })
                    }
                }
            }
            else -> {
                val movies = remember(revision, vodLoading) { snapshotMovies() }
                val series = remember(revision, vodLoading) { snapshotSeries() }
                val live = remember(revision) { runCatching { repository.liveItems() }.getOrDefault(emptyList()) }
                val liveCats = remember(revision) { runCatching { repository.categories(ContentKind.LIVE) }.getOrDefault(emptyList()) }
                val movieCats = remember(revision, vodLoading) { runCatching { repository.categories(ContentKind.VOD) }.getOrDefault(emptyList()) }
                val seriesCats = remember(revision, vodLoading) { runCatching { repository.categories(ContentKind.SERIES) }.getOrDefault(emptyList()) }

                // Prefetch Home ranking off the main thread while browsing other sections.
                // Avoids Movies→Home doing pickTopRated* synchronously during section switch.
                LaunchedEffect(revision, vodLoading, movies.size, series.size) {
                    TvRatings.enqueueBackfill(movies, series)
                    var lastRankMs = -1L
                    var seenRevision = TvRatings.revision.value
                    suspend fun rankIfDue(force: Boolean) {
                        if (!force) {
                            val wait = RankPace.delayMs(lastRankMs, System.currentTimeMillis(), TvRatings.isIdle())
                            if (wait > 0L) delay(wait)
                        }
                        yield()
                        val ranked = withContext(Dispatchers.Default) {
                            pickTopRatedMovies(movies) to pickTopRatedSeries(series)
                        }
                        TopRatedCache.put(revision, seenRevision, ranked.first, ranked.second)
                        lastRankMs = System.currentTimeMillis()
                    }
                    rankIfDue(force = true)
                    TvRatings.revision.collect { rev ->
                        if (rev == seenRevision) return@collect
                        seenRevision = rev
                        rankIfDue(force = false)
                    }
                }

                Box(Modifier.fillMaxSize().background(TipBg)) {
                Column(Modifier.fillMaxSize()) {
                    TopBanner()
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        DesktopSidebar(
                            section = section,
                            refreshing = refreshing,
                            // Block sidebar from stealing focus while detail is open or poster restore pending.
                            focusEnabled = detailItem == null && !pendingFocusRestore,
                            onSection = {
                                if (it != section) {
                                    // Bound FocusRequester map growth from Movies/Series grids.
                                    posterFocusRequesters.clear()
                                }
                                val liveFamily = setOf(DesktopNavSection.LIVE, DesktopNavSection.GUIDE)
                                val keepLiveFilters = section in liveFamily && it in liveFamily
                                section = it
                                if (!keepLiveFilters) {
                                    search = ""
                                    categoryId = null
                                }
                                // Never leave restore pending across section changes (blocks sidebar + content).
                                pendingFocusRestore = false
                            },
                            onRefresh = {
                                scope.launch {
                                    refreshing = true
                                    statusMessage = "Refreshing…"
                                    runCatching { repository.ensureCatalogLoaded(force = true) }
                                        .onSuccess {
                                            statusMessage = repository.lastWarning ?: "Data updated"
                                            error = null
                                        }
                                        .onFailure {
                                            statusMessage = it.message
                                            error = it.message
                                        }
                                    refreshing = false
                                }
                            },
                            onChangeSource = { showOnboarding = true },
                            modifier = Modifier
                                .width(TipDimens.SidebarWidth)
                                .fillMaxHeight()
                                .background(TipBg)
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(
                                    if (section == DesktopNavSection.GUIDE) Modifier
                                    else Modifier.padding(TipDimens.ContentPad)
                                )
                        ) {
                            statusMessage?.let {
                                Text(it, color = TipAccent, fontSize = TipDimens.BodyMediumSp, modifier = Modifier.padding(bottom = TipDimens.dp(8)))
                            }
                            if (vodLoading) {
                                Text("Loading movies & series…", color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp)
                            }
                            when (section) {
                                DesktopNavSection.HOME -> HomePane(
                                    movies = movies,
                                    series = series,
                                    resume = resume,
                                    warning = repository.lastWarning,
                                    catalogRevision = revision,
                                    restoreFocusId = restoreFocusId,
                                    restoreFocusIndex = restoreFocusIndex,
                                    restoreFocusScope = restoreFocusScope,
                                    pendingFocusRestore = pendingFocusRestore,
                                    posterFocus = { scope, id -> posterFocus(scope, id) },
                                    onRestoreConsumed = { consumeFocusRestore() },
                                    onPlay = { item, index -> openDetailOrPlay(item, index = index, scope = "desk-movies") },
                                    onOpenSeries = { item, index -> openDetailOrPlay(item, index = index, scope = "desk-series") },
                                    onResume = { wp: WatchProgress, index: Int ->
                                        openDetailOrPlay(wp.toMediaItem(), focusKey = wp.id, index = index, scope = "desk-cw")
                                    }
                                )
                                DesktopNavSection.LIVE -> LivePane(
                                    items = live,
                                    categories = liveCats,
                                    search = search,
                                    categoryId = categoryId,
                                    onSearch = { search = it },
                                    onCategory = { categoryId = it },
                                    onPlay = onPlay,
                                    onRecord = { com.totaliptv.pro.dvr.DvrActions.recordNow(context, it) }
                                )
                                DesktopNavSection.MOVIES -> BrowseGridPane(
                                    title = "Movies",
                                    items = movies,
                                    categories = movieCats,
                                    search = search,
                                    categoryId = categoryId,
                                    sort = browseSort,
                                    columns = posterColumns,
                                    restoreFocusId = restoreFocusId,
                                    restoreFocusIndex = restoreFocusIndex,
                                    restoreFocusScope = restoreFocusScope,
                                    pendingFocusRestore = pendingFocusRestore,
                                    posterFocus = { scope, id -> posterFocus(scope, id) },
                                    onRestoreConsumed = { consumeFocusRestore() },
                                    gridScope = "desk-movies-grid",
                                    onSearch = { search = it },
                                    onCategory = { categoryId = it },
                                    onSort = { browseSort = it },
                                    onClick = { item, index ->
                                        openDetailOrPlay(item, index = index, scope = "desk-movies-grid")
                                    }
                                )
                                DesktopNavSection.SERIES -> BrowseGridPane(
                                    title = "Series",
                                    items = series,
                                    categories = seriesCats,
                                    search = search,
                                    categoryId = categoryId,
                                    sort = browseSort,
                                    columns = posterColumns,
                                    restoreFocusId = restoreFocusId,
                                    restoreFocusIndex = restoreFocusIndex,
                                    restoreFocusScope = restoreFocusScope,
                                    pendingFocusRestore = pendingFocusRestore,
                                    posterFocus = { scope, id -> posterFocus(scope, id) },
                                    onRestoreConsumed = { consumeFocusRestore() },
                                    gridScope = "desk-series-grid",
                                    onSearch = { search = it },
                                    onCategory = { categoryId = it },
                                    onSort = { browseSort = it },
                                    onClick = { item, index ->
                                        openDetailOrPlay(item, index = index, scope = "desk-series-grid")
                                    }
                                )
                                DesktopNavSection.FAVORITES -> FavoritesPane(
                                    favorites = favorites,
                                    repository = repository,
                                    columns = posterColumns,
                                    restoreFocusId = restoreFocusId,
                                    restoreFocusIndex = restoreFocusIndex,
                                    restoreFocusScope = restoreFocusScope,
                                    pendingFocusRestore = pendingFocusRestore,
                                    posterFocus = { scope, id -> posterFocus(scope, id) },
                                    onRestoreConsumed = { consumeFocusRestore() },
                                    onOpenDetail = { item, index ->
                                        openDetailOrPlay(item, index = index, scope = "desk-fav")
                                    },
                                    onPlayFavorite = onPlayFavorite
                                )
                                DesktopNavSection.RECORDINGS -> {
                                    TotalIptvProTheme(
                                        appearance = appearance,
                                        accent = accent
                                    ) {
                                        com.totaliptv.pro.ui.dvr.RecordingsScreen(
                                            onPlay = onPlay,
                                            onBack = { section = DesktopNavSection.HOME }
                                        )
                                    }
                                }
                                DesktopNavSection.GUIDE -> {
                                    // Same Classic timeline guide (cats + channel col + program grid).
                                    // Keep Desktop amber chrome (banner/sidebar); Classic content inside.
                                    TotalIptvProTheme(
                                        appearance = appearance,
                                        accent = accent
                                    ) {
                                        EpgGuideScreen(
                                            repository = repository,
                                            onPlay = onPlay,
                                            onBack = { section = DesktopNavSection.HOME },
                                            initialCategoryId = categoryId,
                                            onCategoryChange = { categoryId = it }
                                        )
                                    }
                                }
                                DesktopNavSection.SETTINGS -> DesktopSettingsPane(
                                    repository = repository,
                                    liveCount = live.size,
                                    movieCount = movies.size,
                                    seriesCount = series.size,
                                    onRefresh = {
                                        scope.launch {
                                            refreshing = true
                                            runCatching { repository.ensureCatalogLoaded(force = true) }
                                            refreshing = false
                                        }
                                    },
                                    onChangeSource = { showOnboarding = true },
                                    refreshing = refreshing
                                )
                            }
                        }
                    }
                }

                detailItem?.let { detail ->
                    val store = app?.watchProgress
                    val detailProgress = runCatching {
                        store?.forCatalogItem(detail.id)
                    }.getOrNull()
                    MovieDetailSheet(
                        item = detail,
                        isFavorite = detail.id in favoriteIds ||
                            (detailProgress?.id != null && detailProgress.id in favoriteIds) ||
                            (detailProgress?.catalogId != null && detailProgress.catalogId in favoriteIds),
                        repository = repository,
                        watchProgress = detailProgress,
                        onPlay = {
                            val playTarget = detail
                            dismissDetail(restore = false)
                            onPlay(playTarget)
                        },
                        onPlayItem = { playTarget ->
                            dismissDetail(restore = false)
                            onPlay(playTarget)
                        },
                        onResume = {
                            val playTarget = detailProgress?.toMediaItem() ?: detail
                            dismissDetail(restore = false)
                            onPlay(playTarget)
                        },
                        onPlayFromStart = {
                            runCatching {
                                detailProgress?.id?.let { store?.clear(it) }
                                store?.clear(detail.id)
                                detailProgress?.catalogId?.let { store?.clear(it) }
                            }
                            val playTarget = detail
                            dismissDetail(restore = false)
                            onPlayFromStart(playTarget)
                        },
                        onToggleFavorite = { toggleFavoriteToast(detail) },
                        onDismiss = { dismissDetail(restore = true) }
                    )
                }
                } // Box
            }
        }
    }
}

@Composable
private fun DesktopSidebar(
    section: DesktopNavSection,
    refreshing: Boolean,
    focusEnabled: Boolean = true,
    onSection: (DesktopNavSection) -> Unit,
    onRefresh: () -> Unit,
    onChangeSource: () -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        DesktopNavSection.HOME to "Home",
        DesktopNavSection.LIVE to "Live",
        DesktopNavSection.MOVIES to "Movies",
        DesktopNavSection.SERIES to "Series",
        DesktopNavSection.GUIDE to "TV Guide",
        DesktopNavSection.FAVORITES to "Favorites",
        DesktopNavSection.RECORDINGS to "Recordings",
        DesktopNavSection.SETTINGS to "Settings"
    )
    Column(
        modifier
            .focusProperties { canFocus = focusEnabled }
            .padding(TipDimens.SidebarPad),
        verticalArrangement = Arrangement.spacedBy(TipDimens.NavGap)
    ) {
        Text("TOTAL IPTV PRO", color = TipAmber, fontWeight = FontWeight.Bold, fontSize = TipDimens.TitleMediumSp)
        Text("Android TV", color = TipGoldMuted, fontSize = TipDimens.SubBrandSp)
        Spacer(Modifier.height(TipDimens.dp(12)))
        items.forEach { (sec, label) ->
            val selected = section == sec
            TipFocusable(
                onClick = { onSection(sec) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties { canFocus = focusEnabled }
            ) { focused ->
                Text(
                    label,
                    color = when {
                        selected -> TipAmber
                        focused -> TipAccent
                        else -> TipGoldText
                    },
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = TipDimens.BodyLargeSp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                selected -> TipAmber.copy(alpha = 0.25f)
                                focused -> TipSurfaceAlt
                                else -> TipSurfaceAlt.copy(alpha = 0.55f)
                            },
                            RoundedCornerShape(TipDimens.NavCorner)
                        )
                        .padding(horizontal = TipDimens.NavItemPadH, vertical = TipDimens.NavItemPadV)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        TipFocusable(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth().focusProperties { canFocus = focusEnabled }
        ) { focused ->
            Text(
                if (refreshing) "Updating…" else "Update",
                color = TipOnAmber,
                fontWeight = FontWeight.Bold,
                fontSize = TipDimens.LabelLargeSp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (focused) TipAccent else TipAmber, RoundedCornerShape(TipDimens.dp(8)))
                    .padding(horizontal = TipDimens.NavItemPadH, vertical = TipDimens.NavItemPadV)
            )
        }
        TipFocusable(
            onClick = onChangeSource,
            modifier = Modifier.fillMaxWidth().focusProperties { canFocus = focusEnabled }
        ) { focused ->
            Text(
                "Change source",
                color = if (focused) TipAccent else TipGoldMuted,
                fontSize = TipDimens.LabelLargeSp,
                modifier = Modifier.padding(horizontal = TipDimens.NavItemPadH, vertical = TipDimens.dp(10))
            )
        }
    }
}
