package com.totaliptv.pro.ui.home

import android.util.Log
import android.widget.Toast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.totaliptv.pro.data.LiveChannelMapping
import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.EpgNowNext
import com.totaliptv.pro.data.model.FavoriteRef
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.model.WatchProgress
import com.totaliptv.pro.data.local.WatchProgressStore
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.local.AppPreferences
import com.totaliptv.pro.data.model.PlaylistSource
import com.totaliptv.pro.data.model.SourceType
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.data.repo.CatalogSort
import com.totaliptv.pro.dvr.DvrActions
import com.totaliptv.pro.dvr.DvrRecordUi
import com.totaliptv.pro.ui.browse.BrowseSection
import com.totaliptv.pro.ui.components.AppTopNav
import com.totaliptv.pro.ui.components.CategoryRailItem
import com.totaliptv.pro.ui.components.ErrorText
import com.totaliptv.pro.ui.components.FeaturedNowPanel
import com.totaliptv.pro.ui.components.HeroFeatureBanner
import com.totaliptv.pro.ui.components.MovieDetailSheet
import com.totaliptv.pro.ui.components.LiveChannelCard
import com.totaliptv.pro.ui.components.PosterCard
import com.totaliptv.pro.ui.components.SectionRowLabel
import com.totaliptv.pro.ui.components.SortChip
import com.totaliptv.pro.ui.components.TopBarChip
import com.totaliptv.pro.ui.player.GameDayPicker
import com.totaliptv.pro.ui.theme.ClassicDimens
import com.totaliptv.pro.ui.theme.BrandBlue
import com.totaliptv.pro.ui.theme.tipScreenBrush
import com.totaliptv.pro.ui.theme.CinemaBgElevated
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import com.totaliptv.pro.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class HubTab { Home, Live, Movies, Series }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: CatalogRepository,
    onOpenSection: (BrowseSection) -> Unit,
    onOpenFavorite: (FavoriteRef) -> Unit,
    onOpenSettings: () -> Unit,
    onAddSource: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenRecordings: () -> Unit = {},
    onPlayItem: (MediaItem) -> Unit,
    onPlayFromStart: (MediaItem) -> Unit = onPlayItem
) {
    var hubTab by remember { mutableStateOf(HubTab.Home) }
    var catalogSort by remember { mutableStateOf(CatalogSort.RECENTLY_ADDED) }
    var liveCatCount by remember { mutableIntStateOf(0) }
    var vodCatCount by remember { mutableIntStateOf(0) }
    var seriesCatCount by remember { mutableIntStateOf(0) }
    var favorites by remember { mutableStateOf<List<FavoriteRef>>(emptyList()) }
    var sources by remember { mutableStateOf<List<PlaylistSource>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var detailItem by remember { mutableStateOf<MediaItem?>(null) }
    var showGameDay by remember { mutableStateOf(false) }
    // Restore D-pad focus to the *same* poster (stable media id + row/grid index).
    var restoreFocusId by remember { mutableStateOf<String?>(null) }
    var restoreFocusIndex by remember { mutableIntStateOf(-1) }
    var restoreFocusScope by remember { mutableStateOf<String?>(null) }
    var pendingFocusRestore by remember { mutableStateOf(false) }
    // Per poster FocusRequester keyed by "scope:id" so CW/new/top never share one node.
    val posterFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun posterFocus(scope: String, id: String): FocusRequester =
        posterFocusRequesters.getOrPut("$scope:$id") { FocusRequester() }
    val catalogRevision by repository.catalogRevision.collectAsState()
    val pillFocus = remember { FocusRequester() }
    val clockFmt = remember { SimpleDateFormat("h:mm a | MMM d", Locale.getDefault()) }
    var clockText by remember { mutableStateOf(clockFmt.format(Date())) }
    val scope = rememberCoroutineScope()
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val context = LocalContext.current
    val appPrefs = (context.applicationContext as? TotalIptvProApp)?.preferences
    val posterColumns by (appPrefs?.posterColumns ?: kotlinx.coroutines.flow.flowOf(6))
        .collectAsState(initial = 6)
    var refreshStatus by remember { mutableStateOf<String?>(null) }

    fun triggerRefreshData() {
        Toast.makeText(context, "Refreshing...", Toast.LENGTH_SHORT).show()
        refreshStatus = "Refreshing..."
        reloadToken += 1
    }

    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }

    fun toggleFavoriteToast(item: MediaItem) {
        val wasFav = item.id in favoriteIds
        scope.launch {
            repository.toggleFavorite(item)
            Toast.makeText(
                context,
                if (wasFav) "Removed from favorites" else "Added to favorites",
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    fun refreshFromCache() {
        liveCatCount = repository.categories(ContentKind.LIVE).size
        vodCatCount = repository.categories(ContentKind.VOD).size
        seriesCatCount = repository.categories(ContentKind.SERIES).size
    }

    LaunchedEffect(Unit) {
        repository.favorites.collectLatest { favorites = it }
    }
    LaunchedEffect(Unit) {
        repository.sources.collectLatest { sources = it }
    }
    LaunchedEffect(Unit) {
        while (true) {
            clockText = clockFmt.format(Date())
            kotlinx.coroutines.delay(30_000)
        }
    }

    LaunchedEffect(catalogRevision) {
        if (catalogRevision <= 0) return@LaunchedEffect
        refreshFromCache()
        warning = repository.lastWarning
        if (liveCatCount > 0 || vodCatCount > 0 || seriesCatCount > 0) {
            error = null
            loading = false
        }
    }

    LaunchedEffect(reloadToken) {
        loading = true
        error = null
        warning = null
        val userRefresh = reloadToken > 0
        if (userRefresh) {
            refreshStatus = "Refreshing..."
        }
        try {
            repository.ensureCatalogLoaded(force = userRefresh)
            refreshFromCache()
            warning = repository.lastWarning
            if (liveCatCount > 0 || vodCatCount > 0 || seriesCatCount > 0) {
                error = null
                if (userRefresh) {
                    val okMsg = warning ?: "Data updated"
                    refreshStatus = if (warning != null) warning else "Data updated"
                    Toast.makeText(context, okMsg, Toast.LENGTH_SHORT).show()
                }
            } else {
                error = "No playlist loaded. Add a source in Settings."
                if (userRefresh) {
                    refreshStatus = error
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                }
            }
        } catch (t: Throwable) {
            refreshFromCache()
            if (liveCatCount > 0 || vodCatCount > 0 || seriesCatCount > 0) {
                warning = t.message ?: "Partial load - showing available catalog"
                error = null
                if (userRefresh) {
                    refreshStatus = warning
                    Toast.makeText(context, warning, Toast.LENGTH_LONG).show()
                }
            } else {
                error = t.message ?: "Failed to load catalog"
                if (userRefresh) {
                    refreshStatus = error
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                }
            }
        } finally {
            if (liveCatCount > 0 || vodCatCount > 0 || seriesCatCount > 0 || error != null) {
                loading = false
            }
            if (userRefresh && refreshStatus == "Refreshing...") {
                refreshStatus = null
            }
        }
    }

    // Poster scroll+focus restore runs inside HomeTabContent / VodMainPane (they own list state).

    LaunchedEffect(loading, error, liveCatCount, hubTab) {
        // Do not yank focus to nav pills while a detail sheet is open or a poster restore is pending.
        if (detailItem != null || pendingFocusRestore) return@LaunchedEffect
        if (!loading && error == null && (liveCatCount > 0 || vodCatCount > 0 || seriesCatCount > 0)) {
            runCatching { pillFocus.requestFocus() }
        }
    }

    val sourceBadge = remember(sources) {
        val xtream = sources.firstOrNull { it.type == SourceType.XTREAM }
        when {
            xtream != null -> Pair(xtream.xtreamUsername ?: "User", "Xtream")
            sources.isNotEmpty() -> Pair(sources.first().name, "M3U")
            else -> null
        }
    }

    val browseKind = when (hubTab) {
        HubTab.Live -> ContentKind.LIVE
        HubTab.Movies -> ContentKind.VOD
        HubTab.Series -> ContentKind.SERIES
        HubTab.Home -> null
    }

    val categories: List<Category> = remember(hubTab, catalogRevision) {
        when (hubTab) {
            HubTab.Live -> repository.categories(ContentKind.LIVE)
            HubTab.Movies -> repository.categories(ContentKind.VOD)
            HubTab.Series -> repository.categories(ContentKind.SERIES)
            HubTab.Home -> emptyList()
        }
    }

    val categoryCounts: Map<String, Int> = remember(categories, catalogRevision) {
        categories.associate { it.id to repository.itemsForCategory(it.id).size }
    }

    var selectedCategoryId by remember(hubTab, categories) {
        mutableStateOf(categories.firstOrNull()?.id)
    }

    // Keep displayItems in the same remember-key as category/tab/sort so the grid cannot
    // render a previous category's rows (LazyVerticalGrid keys would then bind clicks
    // to leftover items while the header already shows the new category).
    val displayItems: List<MediaItem> = remember(selectedCategoryId, hubTab, catalogRevision, catalogSort) {
        when (hubTab) {
            HubTab.Home -> emptyList()
            HubTab.Live -> {
                val aligned = LiveChannelMapping.filterLiveChannels(
                    repository.liveItems(),
                    selectedCategoryId
                )
                if (catalogSort == CatalogSort.RECENTLY_ADDED) aligned
                else repository.sortCatalogItems(aligned, catalogSort)
            }
            else -> {
                val base = selectedCategoryId?.let { repository.itemsForCategory(it) }.orEmpty()
                repository.sortCatalogItems(base, catalogSort)
            }
        }
    }

    var focusedChannel by remember(displayItems) { mutableStateOf(displayItems.firstOrNull()) }
    var featuredEpg by remember { mutableStateOf(EpgNowNext()) }
    var channelEpgMap by remember(displayItems) { mutableStateOf<Map<String, EpgNowNext>>(emptyMap()) }

    // Home rails
    var heroItem by remember { mutableStateOf<MediaItem?>(null) }
    var newlyAdded by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var topPicks by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var continueWatching by remember { mutableStateOf<List<WatchProgress>>(emptyList()) }
    var progressById by remember { mutableStateOf<Map<String, WatchProgress>>(emptyMap()) }

    fun openDetailFromPoster(
        item: MediaItem,
        focusKey: String = item.id,
        index: Int = -1,
        scope: String = "grid"
    ) {
        restoreFocusId = focusKey
        restoreFocusIndex = index
        restoreFocusScope = scope
        pendingFocusRestore = false
        val parentId = progressById[item.id]?.catalogId
            ?: continueWatching.find { it.id == item.id }?.catalogId
        val parent = parentId?.let { repository.itemById(it) }
        detailItem = parent ?: item
    }

    fun dismissDetail(restore: Boolean) {
        detailItem = null
        pendingFocusRestore = restore && restoreFocusId != null
    }

    fun consumeFocusRestore() {
        pendingFocusRestore = false
    }
    val appCtx = context.applicationContext
    fun progressStore(): WatchProgressStore =
        (appCtx as? TotalIptvProApp)?.watchProgress ?: WatchProgressStore(appCtx)
    fun reloadContinueWatching() {
        val loaded = runCatching {
            val store = progressStore()
            val raw = store.all()
            val list = store.continueWatching()
            android.util.Log.i(
                "TotalIPTV.Progress",
                "reloadContinueWatching raw=${raw.size} resumable=${list.size} hub=$hubTab sample=${list.take(3).joinToString { it.id + "@" + it.positionMs }}"
            )
            list to store.progressByCatalogId()
        }.onFailure {
            android.util.Log.e("TotalIPTV.Progress", "reloadContinueWatching failed", it)
        }.getOrNull()
        if (loaded != null) {
            continueWatching = loaded.first
            progressById = loaded.second
        }
    }

    // Enrich VOD posters asynchronously. Do NOT remap the full display list on each
    // override update (that recomposes the whole LazyVerticalGrid and causes scroll jank).
    var posterOverrides by remember { mutableStateOf<Map<String, MediaItem>>(emptyMap()) }

    // Always refresh progress independently of poster network (root cause fix:
    // prior code called reloadContinueWatching AFTER resolvePoster; a hang/throw
    // left the Continue watching row empty even when the store had entries).
    LaunchedEffect(Unit) {
        reloadContinueWatching()
    }
    LaunchedEffect(hubTab) {
        if (hubTab == HubTab.Home || hubTab == HubTab.Movies || hubTab == HubTab.Series) {
            reloadContinueWatching()
        }
    }
    LaunchedEffect(hubTab, catalogRevision) {
        if (hubTab != HubTab.Home) return@LaunchedEffect
        reloadContinueWatching()
        val newest = repository.newlyAddedMovies(24)
        newlyAdded = newest
        topPicks = newest.drop(1).ifEmpty { newest }
        heroItem = repository.featuredHeroItem()?.let { item ->
            if (item.artworkUrl().isNullOrBlank() && item.xtreamStreamId != null) {
                runCatching { repository.resolvePoster(item) }.getOrDefault(item)
            } else item
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Refresh whenever returning from player, regardless of current tab,
                // so Home shows the row immediately when selected.
                reloadContinueWatching()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(displayItems, hubTab) {
        if (hubTab != HubTab.Movies && hubTab != HubTab.Series) {
            posterOverrides = emptyMap()
            return@LaunchedEffect
        }
        // Cap + chunk: resolving the entire category on the main dispatcher storms the UI.
        val missing = displayItems.asSequence()
            .filter { it.artworkUrl().isNullOrBlank() && it.xtreamStreamId != null }
            .take(64)
            .toList()
        if (missing.isEmpty()) return@LaunchedEffect
        val acc = LinkedHashMap<String, MediaItem>()
        for (chunk in missing.chunked(8)) {
            for (item in chunk) {
                val resolved = repository.resolvePoster(item)
                if (!resolved.artworkUrl().isNullOrBlank()) {
                    acc[item.id] = resolved
                }
            }
            if (acc.isNotEmpty()) posterOverrides = acc.toMap()
            kotlinx.coroutines.yield()
        }
    }

    LaunchedEffect(focusedChannel?.id, hubTab) {
        featuredEpg = EpgNowNext()
        val ch = focusedChannel
        if (hubTab == HubTab.Live && ch != null && repository.hasXtreamEpg()) {
            featuredEpg = runCatching { repository.epgForChannel(ch) }.getOrDefault(EpgNowNext())
            channelEpgMap = channelEpgMap + (ch.id to featuredEpg)
        }
    }

    LaunchedEffect(categories) {
        if (categories.isNotEmpty() && selectedCategoryId == null) {
            selectedCategoryId = categories.first().id
        }
    }

        fun playMedia(item: MediaItem, startOver: Boolean = false) {
        // Resolve by id at click time - never play by list index / focused neighbor.
        val clickedId = item.id
        val bound = repository.playableFrom(repository.itemById(clickedId) ?: item)
        Log.i(
            "TotalIPTV.Live",
            "click name=${bound.name} id=${bound.id} sid=${bound.xtreamStreamId} num=${bound.channelNum} url=${bound.streamUrl} startOver=$startOver"
        )
        val sink: (MediaItem) -> Unit = if (startOver) onPlayFromStart else onPlayItem
        // Already a concrete episode leaf from Continue watching / Resume - do not re-resolve.
        if (bound.id.startsWith("series-ep-") && bound.streamUrl.isNotBlank()) {
            sink(bound)
            return
        }
        if (bound.kind == ContentKind.SERIES) {
            scope.launch {
                val playable = repository.resolveSeriesPlayable(bound) ?: bound
                sink(playable)
            }
        } else {
            // Live/VOD: open synchronously so a later focus change cannot swap the target.
            sink(bound)
        }
    }

    val selectedTabLabel = when (hubTab) {
        HubTab.Home -> "Home"
        HubTab.Live -> "Live"
        HubTab.Movies -> "Movies"
        HubTab.Series -> "Series"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tipScreenBrush())
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopNav(
                brandTitle = "Total IPTV Pro",
                clockText = clockText,
                selectedTab = selectedTabLabel,
                onSelectTab = { label ->
                    val next = when (label) {
                        "Home" -> HubTab.Home
                        "Live" -> HubTab.Live
                        "Movies" -> HubTab.Movies
                        "Series" -> HubTab.Series
                        else -> hubTab
                    }
                    if (next != hubTab) posterFocusRequesters.clear()
                    hubTab = next
                },
                onOpenSearch = onOpenSearch,
                onOpenSettings = onOpenSettings,
                onRefreshData = { triggerRefreshData() },
                userBadge = sourceBadge?.first,
                sourceKind = sourceBadge?.second,
                focusRequester = pillFocus
            )

            refreshStatus?.let { status ->
                Text(
                    text = status,
                    color = if (status.startsWith("Refreshing")) BrandBlue else WarningAmber,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 1.dp)
                )
            }

            warning?.let { w ->
                Text(
                    text = w,
                    color = WarningAmber,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 1.dp)
                )
            }

            when {
                loading && liveCatCount == 0 && vodCatCount == 0 && seriesCatCount == 0 -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Loading playlist…",
                            color = OnCinemaMuted,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                error != null && liveCatCount == 0 && vodCatCount == 0 && seriesCatCount == 0 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(48.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        ErrorText(error!!, modifier = Modifier.padding(0.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { triggerRefreshData() }) { Text("Retry") }
                            Button(onClick = onAddSource) { Text("Add source") }
                            Button(onClick = onOpenSettings) { Text("Settings") }
                        }
                    }
                }
                hubTab == HubTab.Home -> {
                    HomeTabContent(
                        heroItem = heroItem,
                        newlyAdded = newlyAdded,
                        topPicks = topPicks,
                        continueWatching = continueWatching,
                        progressById = progressById,
                        favorites = favorites,
                        favoriteIds = favoriteIds,
                        restoreFocusId = restoreFocusId,
                        restoreFocusIndex = restoreFocusIndex,
                        restoreFocusScope = restoreFocusScope,
                        pendingFocusRestore = pendingFocusRestore,
                        posterFocus = { scope, id -> posterFocus(scope, id) },
                        onRestoreConsumed = { consumeFocusRestore() },
                        onPlay = { playMedia(it) },
                        onOpenDetail = { item, index, scope ->
                            openDetailFromPoster(item, focusKey = item.id, index = index, scope = scope)
                        },
                        onToggleFavorite = { toggleFavoriteToast(it) },
                        onOpenFavorite = onOpenFavorite,
                        onOpenLive = { hubTab = HubTab.Live },
                        onOpenMovies = { hubTab = HubTab.Movies },
                        onOpenSeries = { hubTab = HubTab.Series },
                        onPreviewHero = { item -> openDetailFromPoster(item, scope = "hero") }
                    )
                }
                else -> {
                    // Live / Movies / Series share left rail + main pane
                    Row(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .width(ClassicDimens.CategoryRailWidth)
                                .fillMaxHeight()
                                .background(CinemaBgElevated)
                                .padding(vertical = 6.dp, horizontal = 6.dp)
                        ) {
                            if (hubTab == HubTab.Live) {
                                Text(
                                    text = "Quick",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnCinemaMuted,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                                CategoryRailItem(
                                    title = "Favorites",
                                    selected = false,
                                    count = favorites.size,
                                    onClick = { onOpenSection(BrowseSection.Favorites) }
                                )
                                CategoryRailItem(
                                    title = "TV Guide",
                                    selected = false,
                                    onClick = onOpenGuide
                                )
                                CategoryRailItem(
                                    title = "Game Day",
                                    selected = false,
                                    onClick = { showGameDay = true }
                                )
                                CategoryRailItem(
                                    title = "Recordings",
                                    selected = false,
                                    onClick = onOpenRecordings
                                )
                                CategoryRailItem(
                                    title = "Refresh data",
                                    selected = false,
                                    onClick = { triggerRefreshData() }
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            Text(
                                text = "Categories  |  ${categories.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = OnCinemaMuted,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                            if (categories.isEmpty()) {
                                Text(
                                    text = when (hubTab) {
                                        HubTab.Series -> "No series categories yet."
                                        HubTab.Movies -> "No movie categories loaded."
                                        else -> "No categories."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = OnCinemaMuted,
                                    modifier = Modifier.padding(12.dp)
                                )
                            } else {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    contentPadding = PaddingValues(bottom = 16.dp)
                                ) {
                                    items(categories, key = { it.id }) { cat ->
                                        CategoryRailItem(
                                            title = cat.name,
                                            selected = cat.id == selectedCategoryId,
                                            count = categoryCounts[cat.id],
                                            onClick = { selectedCategoryId = cat.id }
                                        )
                                    }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            when (hubTab) {
                                HubTab.Live -> LiveMainPane(
                                    displayItems = displayItems,
                                    categories = categories,
                                    selectedCategoryId = selectedCategoryId,
                                    focusedChannel = focusedChannel,
                                    featuredEpg = featuredEpg,
                                    channelEpgMap = channelEpgMap,
                                    catalogSort = catalogSort,
                                    onSort = { catalogSort = it },
                                    timeFmt = timeFmt,
                                    onFocusChannel = { focusedChannel = it },
                                    onPlay = { playMedia(it) },
                                    onOpenGuide = onOpenGuide,
                                    onRecordNow = { DvrActions.recordNow(context, it) },
                                    onGameDay = { showGameDay = true }
                                )
                                HubTab.Movies, HubTab.Series -> VodMainPane(
                                    isSeries = hubTab == HubTab.Series,
                                    displayItems = displayItems,
                                    posterOverrides = posterOverrides,
                                    categoriesEmpty = categories.isEmpty(),
                                    catalogSort = catalogSort,
                                    onSort = { catalogSort = it },
                                    favoriteIds = favoriteIds,
                                    progressById = progressById,
                                    selectedCategoryId = selectedCategoryId,
                                    restoreFocusId = restoreFocusId,
                                    restoreFocusIndex = restoreFocusIndex,
                                    restoreFocusScope = restoreFocusScope,
                                    pendingFocusRestore = pendingFocusRestore,
                                    posterFocus = { scope, id -> posterFocus(scope, id) },
                                    onRestoreConsumed = { consumeFocusRestore() },
                                    onPlay = { playMedia(it) },
                                    onOpenDetail = { item, index ->
                                        openDetailFromPoster(item, focusKey = item.id, index = index, scope = "vod-grid")
                                    },
                                    onToggleFavorite = { toggleFavoriteToast(it) },
                posterColumns = posterColumns
                                )
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }

        detailItem?.let { detail ->
            val detailProgress = progressById[detail.id]
                ?: detail.let { d -> progressById.entries.firstOrNull { e ->
                    e.value.catalogId == d.id || e.value.id == d.id
                }?.value }
                ?: runCatching { progressStore().forCatalogItem(detail.id) }.getOrNull()
            MovieDetailSheet(
                item = detail,
                isFavorite = detail.id in favoriteIds ||
                    (detailProgress?.id in favoriteIds) ||
                    (detailProgress?.catalogId != null && detailProgress.catalogId in favoriteIds),
                repository = repository,
                watchProgress = detailProgress,
                onPlay = {
                    val playTarget = detail
                    dismissDetail(restore = false)
                    playMedia(playTarget)
                },
                onPlayItem = { playTarget ->
                    dismissDetail(restore = false)
                    playMedia(playTarget)
                },
                onResume = {
                    val playTarget = detailProgress?.toMediaItem() ?: detail
                    dismissDetail(restore = false)
                    playMedia(playTarget, startOver = false)
                },
                onPlayFromStart = {
                    // Restart: clear leaf + catalog keys, then EXTRA_START_OVER.
                    runCatching {
                        val store = progressStore()
                        detailProgress?.id?.let { store.clear(it) }
                        store.clear(detail.id)
                        detailProgress?.catalogId?.let { store.clear(it) }
                    }
                    val playTarget = detail
                    dismissDetail(restore = false)
                    playMedia(playTarget, startOver = true)
                },
                onToggleFavorite = { toggleFavoriteToast(detail) },
                onDismiss = { dismissDetail(restore = true) }
            )
        }
        if (showGameDay && hubTab == HubTab.Live) {
            GameDayPicker(
                channels = repository.liveItems(),
                initialLeft = focusedChannel,
                onDismiss = { showGameDay = false }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeTabContent(
    heroItem: MediaItem?,
    newlyAdded: List<MediaItem>,
    topPicks: List<MediaItem>,
    continueWatching: List<WatchProgress>,
    progressById: Map<String, WatchProgress>,
    favorites: List<FavoriteRef>,
    favoriteIds: Set<String>,
    restoreFocusId: String?,
    restoreFocusIndex: Int,
    restoreFocusScope: String?,
    pendingFocusRestore: Boolean,
    posterFocus: (scope: String, id: String) -> FocusRequester,
    onRestoreConsumed: () -> Unit,
    onPlay: (MediaItem) -> Unit,
    onOpenDetail: (MediaItem, index: Int, scope: String) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onOpenFavorite: (FavoriteRef) -> Unit,
    onOpenLive: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeries: () -> Unit,
    onPreviewHero: (MediaItem) -> Unit
) {
    val homeListState = rememberLazyListState()
    val cwRowState = rememberLazyListState()
    val newRowState = rememberLazyListState()
    val topRowState = rememberLazyListState()
    val favRowState = rememberLazyListState()
    val homeScopes = setOf("cw", "new", "top", "fav")
    LaunchedEffect(pendingFocusRestore, restoreFocusId, restoreFocusIndex, restoreFocusScope) {
        if (!pendingFocusRestore) return@LaunchedEffect
        val scope = restoreFocusScope ?: return@LaunchedEffect
        val id = restoreFocusId ?: return@LaunchedEffect
        if (scope !in homeScopes) return@LaunchedEffect
        // Do NOT consume yet — keep pendingFocusRestore so nav pills cannot steal focus.
        val idx = restoreFocusIndex.coerceAtLeast(0)
        var focused = false
        for (attempt in 0 until 12) {
            val rowOrdinal = when (scope) {
                "cw" -> 1
                "new" -> if (continueWatching.isNotEmpty()) 3 else 1
                "top" -> {
                    var o = 1
                    if (continueWatching.isNotEmpty()) o += 2
                    if (newlyAdded.isNotEmpty()) o += 2
                    o
                }
                "fav" -> {
                    var o = 1
                    if (continueWatching.isNotEmpty()) o += 2
                    if (newlyAdded.isNotEmpty()) o += 2
                    if (topPicks.isNotEmpty()) o += 2
                    o
                }
                else -> 0
            }
            runCatching { homeListState.scrollToItem(rowOrdinal.coerceAtLeast(0)) }
            when (scope) {
                "cw" -> runCatching { cwRowState.scrollToItem(idx) }
                "new" -> runCatching { newRowState.scrollToItem(idx) }
                "top" -> runCatching { topRowState.scrollToItem(idx) }
                "fav" -> runCatching { favRowState.scrollToItem(idx) }
            }
            kotlinx.coroutines.yield()
            kotlinx.coroutines.delay(if (attempt == 0) 50L else 70L)
            focused = runCatching {
                posterFocus(scope, id).requestFocus()
                true
            }.getOrDefault(false)
            if (focused) break
        }
        onRestoreConsumed()
    }
    LazyColumn(
        state = homeListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
    ) {
        item {
            if (heroItem != null) {
                HeroFeatureBanner(
                    title = heroItem.name,
                    imageUrl = heroItem.artworkUrl(),
                    metaLine = heroItem.groupTitle ?: "From your library",
                    onPlay = { onPlay(heroItem) },
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .height(ClassicDimens.HeroHeight),
                    rating = heroItem.displayRating(),
                    onPreview = { onPreviewHero(heroItem) }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ClassicDimens.HeroEmptyHeight)
                        .padding(bottom = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column {
                        Text(
                            "Welcome to Total IPTV Pro",
                            style = MaterialTheme.typography.headlineSmall,
                            color = OnCinema,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Live TV loads first — Movies & Series appear when ready.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnCinemaMuted,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 12.dp)
                        ) {
                            Button(onClick = onOpenLive) { Text("Browse Live") }
                            Button(onClick = onOpenMovies) { Text("Browse Movies") }
                            Button(onClick = onOpenSeries) { Text("Browse Series") }
                        }
                    }
                }
            }
        }
        if (continueWatching.isNotEmpty()) {
            item { SectionRowLabel("Continue watching") }
            item {
                LazyRow(
                    state = cwRowState,
                    horizontalArrangement = Arrangement.spacedBy(ClassicDimens.PosterRowGap),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    itemsIndexed(continueWatching, key = { _, it -> "cw-${it.id}" }) { index, prog ->
                        val pct = (prog.fraction() * 100).toInt().coerceIn(1, 99)
                        val media = prog.toMediaItem()
                        PosterCard(
                            title = prog.title,
                            imageUrl = prog.logoUrl,
                            onClick = {
                                // Prefer detail sheet so Resume is visible; fall back to play.
                                // Open catalog parent when known so poster/title match the Movies/Series grid.
                                if (media.kind == ContentKind.VOD || media.kind == ContentKind.SERIES) {
                                    onOpenDetail(media, index, "cw")
                                } else {
                                    onPlay(media)
                                }
                            },
                            isFavorite = prog.id in favoriteIds || (prog.catalogId != null && prog.catalogId in favoriteIds),
                            onLongClick = { onToggleFavorite(media) },
                            progressPercent = pct,
                            focusRequester = posterFocus("cw", prog.id)
                        )
                    }
                }
            }
        }
        if (newlyAdded.isNotEmpty()) {
            item { SectionRowLabel("Newly added") }
            item {
                LazyRow(
                    state = newRowState,
                    horizontalArrangement = Arrangement.spacedBy(ClassicDimens.PosterRowGap),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    itemsIndexed(newlyAdded, key = { _, it -> it.id }) { index, item ->
                        val prog = progressById[item.id]
                        val pct = prog?.let { (it.fraction() * 100).toInt().coerceIn(1, 99) }
                        PosterCard(
                            title = item.name,
                            imageUrl = item.artworkUrl(),
                            onClick = {
                                if (item.kind == ContentKind.VOD || item.kind == ContentKind.SERIES) {
                                    onOpenDetail(item, index, "new")
                                } else {
                                    onPlay(item)
                                }
                            },
                            isFavorite = item.id in favoriteIds,
                            onLongClick = { onToggleFavorite(item) },
                            rating = item.displayRating(),
                            progressPercent = pct,
                            focusRequester = posterFocus("new", item.id)
                        )
                    }
                }
            }
        }
        if (topPicks.isNotEmpty()) {
            item { SectionRowLabel("Top picks") }
            item {
                LazyRow(
                    state = topRowState,
                    horizontalArrangement = Arrangement.spacedBy(ClassicDimens.PosterRowGap),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    itemsIndexed(topPicks, key = { _, it -> "tp-${it.id}" }) { index, item ->
                        val prog = progressById[item.id]
                        val pct = prog?.let { (it.fraction() * 100).toInt().coerceIn(1, 99) }
                        PosterCard(
                            title = item.name,
                            imageUrl = item.artworkUrl(),
                            onClick = {
                                if (item.kind == ContentKind.VOD || item.kind == ContentKind.SERIES) {
                                    onOpenDetail(item, index, "top")
                                } else {
                                    onPlay(item)
                                }
                            },
                            isFavorite = item.id in favoriteIds,
                            onLongClick = { onToggleFavorite(item) },
                            rating = item.displayRating(),
                            progressPercent = pct,
                            focusRequester = posterFocus("top", item.id)
                        )
                    }
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item { SectionRowLabel("Favorites") }
            item {
                LazyRow(
                    state = favRowState,
                    horizontalArrangement = Arrangement.spacedBy(ClassicDimens.PosterRowGap),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    itemsIndexed(favorites, key = { _, it -> "fav-${it.id}" }) { _, fav ->
                        PosterCard(
                            title = fav.name,
                            imageUrl = fav.logoUrl,
                            onClick = { onOpenFavorite(fav) },
                            isFavorite = true,
                            onLongClick = {
                                onToggleFavorite(
                                    MediaItem(
                                        id = fav.id,
                                        name = fav.name,
                                        streamUrl = fav.streamUrl,
                                        categoryId = null,
                                        kind = fav.kind,
                                        logoUrl = fav.logoUrl
                                    )
                                )
                            },
                            focusRequester = posterFocus("fav", fav.id)
                        )
                    }
                }
            }
        }
        if (newlyAdded.isEmpty() && topPicks.isEmpty() && heroItem == null && continueWatching.isEmpty()) {
            item {
                Text(
                    "Add a playlist to see featured titles and rows here.",
                    color = OnCinemaMuted,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveMainPane(
    displayItems: List<MediaItem>,
    categories: List<Category>,
    selectedCategoryId: String?,
    focusedChannel: MediaItem?,
    featuredEpg: EpgNowNext,
    channelEpgMap: Map<String, EpgNowNext>,
    catalogSort: CatalogSort,
    onSort: (CatalogSort) -> Unit,
    timeFmt: SimpleDateFormat,
    onFocusChannel: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onOpenGuide: () -> Unit,
    onRecordNow: (MediaItem) -> Unit = {},
    onGameDay: () -> Unit = {}
) {
    val ch = focusedChannel
    val context = LocalContext.current
    val dvrSnap by remember(context) {
        (context.applicationContext as TotalIptvProApp).dvr.snapshot
    }.collectAsState()
    val recordLook = DvrRecordUi.appearance(dvrSnap.active, ch?.id, ch?.streamUrl)
    val now = featuredEpg.now
    val next = featuredEpg.next
    val progress = if (now != null) {
        val span = (now.endMs - now.startMs).coerceAtLeast(1L).toFloat()
        ((System.currentTimeMillis() - now.startMs) / span).coerceIn(0f, 1f)
    } else null

    FeaturedNowPanel(
        channelName = ch?.name ?: "Live TV",
        logoUrl = ch?.logoUrl,
        nowTitle = now?.title,
        nowTimeRange = now?.let {
            "${timeFmt.format(Date(it.startMs))} - ${timeFmt.format(Date(it.endMs))}"
        },
        progress = progress,
        synopsis = now?.description,
        comingUp = next?.title,
        onPlay = {
            // Play the focused channel by identity (id), not by grid neighbor position.
            ch?.let { focused -> onPlay(focused) }
        },
        onOpenGuide = onOpenGuide,
        onRecord = ch?.let { focused -> { onRecordNow(focused) } },
        recordActive = recordLook.selected,
        recordLabel = recordLook.label,
        modifier = Modifier.padding(bottom = 6.dp)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "${displayItems.size} channels" +
                (selectedCategoryId?.let { id ->
                    categories.find { it.id == id }?.name?.let { "  |  $it" }
                } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = BrandBlue.copy(alpha = 0.9f)
        )
        Spacer(Modifier.weight(1f))
        TopBarChip(label = "Game Day", onClick = onGameDay, emphasized = true)
        Spacer(Modifier.width(8.dp))
        Text("Sort", style = MaterialTheme.typography.labelSmall, color = OnCinemaMuted)
        SortChip(label = "A–Z", selected = catalogSort == CatalogSort.AZ, onClick = { onSort(CatalogSort.AZ) })
        SortChip(label = "Z–A", selected = catalogSort == CatalogSort.ZA, onClick = { onSort(CatalogSort.ZA) })
        SortChip(
            label = "Newest",
            selected = catalogSort == CatalogSort.RECENTLY_ADDED,
            onClick = { onSort(CatalogSort.RECENTLY_ADDED) }
        )
    }

    if (categories.isEmpty()) {
        EmptyHub("No live categories. Check your playlist or reload.")
    } else if (displayItems.isEmpty()) {
        EmptyHub("No channels in this category.")
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = ClassicDimens.ChannelGridMin),
            contentPadding = PaddingValues(2.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            gridItems(displayItems, key = { it.id }) { item ->
                val epg = channelEpgMap[item.id]
                val p = epg?.now?.let { n ->
                    val span = (n.endMs - n.startMs).coerceAtLeast(1L).toFloat()
                    ((System.currentTimeMillis() - n.startMs) / span).coerceIn(0f, 1f)
                }
                LiveChannelCard(
                    title = item.name,
                    logoUrl = item.logoUrl,
                    nowTitle = epg?.now?.title,
                    progress = p,
                    onFocused = { onFocusChannel(item) },
                    onClick = {
                        // Snapshot id then re-resolve — guards TV focus/click desync on emulator.
                        val id = item.id
                        val snap = item
                        onFocusChannel(snap)
                        onPlay(snap)
                        Log.i("TotalIPTV.Live", "gridClick id=$id name=${snap.name} sid=${snap.xtreamStreamId}")
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VodMainPane(
    isSeries: Boolean,
    displayItems: List<MediaItem>,
    posterOverrides: Map<String, MediaItem> = emptyMap(),
    categoriesEmpty: Boolean,
    catalogSort: CatalogSort,
    onSort: (CatalogSort) -> Unit,
    favoriteIds: Set<String>,
    progressById: Map<String, WatchProgress> = emptyMap(),
    selectedCategoryId: String? = null,
    restoreFocusId: String? = null,
    restoreFocusIndex: Int = -1,
    restoreFocusScope: String? = null,
    pendingFocusRestore: Boolean = false,
    posterFocus: (scope: String, id: String) -> FocusRequester = { _, _ -> FocusRequester() },
    onRestoreConsumed: () -> Unit = {},
    onPlay: (MediaItem) -> Unit,
    onOpenDetail: (MediaItem, index: Int) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    posterColumns: Int = 6
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(selectedCategoryId, catalogSort, isSeries) {
        // Reset scroll only when category/sort changes — not when detail sheet dismisses.
        gridState.scrollToItem(0)
    }
    LaunchedEffect(pendingFocusRestore, restoreFocusId, restoreFocusIndex, restoreFocusScope) {
        if (!pendingFocusRestore) return@LaunchedEffect
        if (restoreFocusScope != "vod-grid") return@LaunchedEffect
        val id = restoreFocusId ?: return@LaunchedEffect
        // Keep pending until poster focus lands (avoids pill/rail steal).
        val idx = restoreFocusIndex.coerceAtLeast(0)
        var focused = false
        for (attempt in 0 until 12) {
            if (idx < displayItems.size) {
                runCatching { gridState.scrollToItem(idx) }
            } else {
                val found = displayItems.indexOfFirst { it.id == id }
                if (found >= 0) runCatching { gridState.scrollToItem(found) }
            }
            kotlinx.coroutines.yield()
            kotlinx.coroutines.delay(if (attempt == 0) 50L else 70L)
            focused = runCatching {
                posterFocus("vod-grid", id).requestFocus()
                true
            }.getOrDefault(false)
            if (focused) break
        }
        onRestoreConsumed()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = if (isSeries) "${displayItems.size} series" else "${displayItems.size} movies",
            style = MaterialTheme.typography.labelMedium,
            color = BrandBlue.copy(alpha = 0.9f)
        )
        Spacer(Modifier.weight(1f))
        Text("Sort", style = MaterialTheme.typography.labelSmall, color = OnCinemaMuted)
        SortChip(label = "A–Z", selected = catalogSort == CatalogSort.AZ, onClick = { onSort(CatalogSort.AZ) })
        SortChip(label = "Z–A", selected = catalogSort == CatalogSort.ZA, onClick = { onSort(CatalogSort.ZA) })
        SortChip(
            label = "Newest",
            selected = catalogSort == CatalogSort.RECENTLY_ADDED,
            onClick = { onSort(CatalogSort.RECENTLY_ADDED) }
        )
    }

    when {
        categoriesEmpty -> EmptyHub(
            if (isSeries) "Series catalogs aren't available for this playlist yet."
            else "Movies are still loading, or this playlist has no VOD."
        )
        displayItems.isEmpty() -> EmptyHub("No titles in this category.")
        else -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(AppPreferences.normalizePosterColumns(posterColumns)),
                state = gridState,
                contentPadding = PaddingValues(2.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                gridItemsIndexed(
                    displayItems,
                    key = { _, it -> it.id },
                    contentType = { _, _ -> "poster" }
                ) { index, item ->
                    val shown = posterOverrides[item.id] ?: item
                    val prog = progressById[item.id]
                    val pct = prog?.let { (it.fraction() * 100).toInt().coerceIn(1, 99) }
                    PosterCard(
                        title = shown.name,
                        imageUrl = shown.artworkUrl(),
                        onClick = { onOpenDetail(item, index) },
                        isFavorite = item.id in favoriteIds,
                        onLongClick = { onToggleFavorite(item) },
                        rating = shown.displayRating(),
                        progressPercent = pct,
                        focusRequester = posterFocus("vod-grid", item.id)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmptyHub(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = OnCinemaMuted,
            modifier = Modifier.padding(32.dp)
        )
    }
}
