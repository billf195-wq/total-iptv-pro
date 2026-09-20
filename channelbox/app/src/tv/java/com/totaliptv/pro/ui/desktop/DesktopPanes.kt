package com.totaliptv.pro.ui.desktop

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.totaliptv.pro.BuildConfig
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.LiveChannelMapping
import com.totaliptv.pro.data.local.AppLayoutMode
import com.totaliptv.pro.data.local.AppPreferences
import com.totaliptv.pro.ui.theme.AccentPreset
import com.totaliptv.pro.ui.theme.AppearanceMode
import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.FavoriteRef
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.model.WatchProgress
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.data.update.AppUpdateChecker
import com.totaliptv.pro.data.update.UpdateCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomePane(
    movies: List<MediaItem>,
    series: List<MediaItem>,
    resume: List<WatchProgress>,
    warning: String?,
    catalogRevision: Int = 0,
    restoreFocusId: String? = null,
    restoreFocusIndex: Int = -1,
    restoreFocusScope: String? = null,
    pendingFocusRestore: Boolean = false,
    posterFocus: (scope: String, id: String) -> FocusRequester = { _, _ -> FocusRequester() },
    onRestoreConsumed: () -> Unit = {},
    onPlay: (MediaItem, index: Int) -> Unit,
    onOpenSeries: (MediaItem, index: Int) -> Unit,
    onResume: (WatchProgress, index: Int) -> Unit
) {
    // Never rank the full catalog synchronously on the first Home frame (Movies→Home ANR).
    // Warm from process cache when revision unchanged; otherwise compute off main after yield.
    var top by remember {
        mutableStateOf(TopRatedCache.movies(catalogRevision) ?: TopRatedRow("Top rated movies", emptyList()))
    }
    var topSeries by remember {
        mutableStateOf(TopRatedCache.series(catalogRevision) ?: TopRatedRow("Top rated series", emptyList()))
    }
    LaunchedEffect(catalogRevision, movies.size, series.size) {
        TopRatedCache.movies(catalogRevision)?.let { cachedM ->
            TopRatedCache.series(catalogRevision)?.let { cachedS ->
                top = cachedM
                topSeries = cachedS
                return@LaunchedEffect
            }
        }
        // Paint Home chrome first; ranking can wait one frame.
        yield()
        val ranked = withContext(Dispatchers.Default) {
            pickTopRatedMovies(movies) to pickTopRatedSeries(series)
        }
        TopRatedCache.put(catalogRevision, ranked.first, ranked.second)
        top = ranked.first
        topSeries = ranked.second
    }
    val homeListState = rememberLazyListState()
    val resumeRowState = rememberLazyListState()
    val moviesRowState = rememberLazyListState()
    val seriesRowState = rememberLazyListState()
    val homeScopes = setOf("desk-cw", "desk-movies", "desk-series")
    LaunchedEffect(pendingFocusRestore, restoreFocusId, restoreFocusIndex, restoreFocusScope) {
        if (!pendingFocusRestore) return@LaunchedEffect
        val scope = restoreFocusScope
        val id = restoreFocusId
        // Always consume — never leave pending stuck (sidebar canFocus=false traps nav).
        if (scope == null || id == null || scope !in homeScopes) {
            onRestoreConsumed()
            return@LaunchedEffect
        }
        val idx = restoreFocusIndex.coerceAtLeast(0)
        var focused = false
        for (attempt in 0 until 8) {
            val rowOrdinal = when (scope) {
                "desk-cw" -> 1
                "desk-movies" -> 2
                "desk-series" -> 3
                else -> 0
            }
            runCatching { homeListState.scrollToItem(rowOrdinal) }
            when (scope) {
                "desk-cw" -> runCatching { resumeRowState.scrollToItem(idx) }
                "desk-movies" -> runCatching { moviesRowState.scrollToItem(idx) }
                "desk-series" -> runCatching { seriesRowState.scrollToItem(idx) }
            }
            kotlinx.coroutines.yield()
            kotlinx.coroutines.delay(if (attempt == 0) 40L else 60L)
            // requestFocus() is Unit here (throws if requester inactive); do not assume Boolean.
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
        verticalArrangement = Arrangement.spacedBy(TipDimens.dp(20))
    ) {
        item {
            PaneTitle("Home")
            Text(
                "Continue watching and top picks from your catalog",
                color = TipGoldMuted,
                fontSize = TipDimens.BodyMediumSp
            )
        }
        item {
            SectionHeader("Continue watching")
            if (resume.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(TipSurface, RoundedCornerShape(TipDimens.NavCorner))
                        .padding(TipDimens.dp(16))
                ) {
                    Text(
                        "Play a movie or series episode — it will show up here.",
                        color = TipGoldMuted,
                        fontSize = TipDimens.BodyMediumSp
                    )
                }
            } else {
                LazyRow(
                    state = resumeRowState,
                    horizontalArrangement = Arrangement.spacedBy(TipDimens.PosterRowGap)
                ) {
                    itemsIndexed(resume.take(24), key = { _, it -> it.id }) { index, entry ->
                        DesktopPosterCard(
                            entry.toMediaItem(),
                            onClick = { onResume(entry, index) },
                            focusRequester = posterFocus("desk-cw", entry.id)
                        )
                    }
                }
            }
        }
        item {
            SectionHeader(top.title)
            if (top.items.isEmpty()) {
                Text("No movies in catalog yet.", color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp)
            } else {
                LazyRow(
                    state = moviesRowState,
                    horizontalArrangement = Arrangement.spacedBy(TipDimens.PosterRowGap)
                ) {
                    itemsIndexed(top.items, key = { _, it -> it.id }) { index, item ->
                        DesktopPosterCard(
                            item,
                            onClick = { onPlay(item, index) },
                            focusRequester = posterFocus("desk-movies", item.id)
                        )
                    }
                }
            }
        }
        item {
            SectionHeader(topSeries.title)
            if (topSeries.items.isEmpty()) {
                Text("No series in catalog yet.", color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp)
            } else {
                LazyRow(
                    state = seriesRowState,
                    horizontalArrangement = Arrangement.spacedBy(TipDimens.PosterRowGap)
                ) {
                    itemsIndexed(topSeries.items, key = { _, it -> it.id }) { index, item ->
                        DesktopPosterCard(
                            item,
                            onClick = { onOpenSeries(item, index) },
                            focusRequester = posterFocus("desk-series", item.id)
                        )
                    }
                }
            }
        }
        if (!warning.isNullOrBlank()) {
            item { Text(warning, color = TipAccent, fontSize = TipDimens.sp(12)) }
        }
        item { Spacer(Modifier.height(TipDimens.dp(24))) }
    }
}

@Composable
fun LivePane(
    items: List<MediaItem>,
    categories: List<Category>,
    search: String,
    categoryId: String?,
    onSearch: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onPlay: (MediaItem) -> Unit
) {
    val filtered = remember(items, search, categoryId) {
        LiveChannelMapping.filterLiveChannels(items, categoryId, search)
    }
    Column(Modifier.fillMaxSize()) {
        FilterBar(
            search = search,
            onSearch = onSearch,
            categories = categories,
            categoryId = categoryId,
            onCategory = onCategory,
            sort = null,
            onSort = null
        )
        Text("${filtered.size} channels", color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp)
        Spacer(Modifier.height(TipDimens.dp(8)))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(TipDimens.dp(6)),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(filtered, key = { it.id }) { item ->
                LiveRowItem(item, onClick = { onPlay(item) })
            }
        }
    }
}

/**
 * Recently-added order for Desktop Movies/Series:
 * 1) [MediaItem.addedMs] when any item has it
 * 2) else descending [MediaItem.xtreamStreamId]
 * 3) else title A-Z
 * Mirrors CatalogRepository.sortByRecentlyAdded so sparse timestamps never empty the grid.
 */
private fun sortDesktopRecentlyAdded(items: List<MediaItem>): List<MediaItem> {
    val anyAdded = items.any { (it.addedMs ?: 0L) > 0L }
    if (anyAdded) {
        return items.sortedWith(
            compareByDescending<MediaItem> { it.addedMs ?: 0L }
                .thenByDescending { it.xtreamStreamId ?: 0 }
                .thenBy { it.name.lowercase() }
        )
    }
    val anySid = items.any { (it.xtreamStreamId ?: 0) > 0 }
    if (anySid) {
        return items.sortedWith(
            compareByDescending<MediaItem> { it.xtreamStreamId ?: 0 }
                .thenBy { it.name.lowercase() }
        )
    }
    return items.sortedBy { it.name.lowercase() }
}

@Composable
fun BrowseGridPane(
    title: String,
    items: List<MediaItem>,
    categories: List<Category>,
    search: String,
    categoryId: String?,
    sort: String,
    columns: Int,
    restoreFocusId: String? = null,
    restoreFocusIndex: Int = -1,
    restoreFocusScope: String? = null,
    pendingFocusRestore: Boolean = false,
    posterFocus: (scope: String, id: String) -> FocusRequester = { _, _ -> FocusRequester() },
    onRestoreConsumed: () -> Unit = {},
    gridScope: String = "desk-grid",
    onSearch: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onSort: (String) -> Unit,
    onClick: (MediaItem, index: Int) -> Unit
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(categoryId, sort, title) {
        gridState.scrollToItem(0)
    }

    val filtered = remember(items, search, categoryId, sort) {
        // Synthetic Newly added categories have no real item.categoryId matches -
        // resolve like CatalogRepository.itemsForCategory instead of filtering to empty.
        val isNewlyAdded = categoryId == CatalogRepository.NEWLY_ADDED_VOD_CATEGORY_ID ||
            categoryId == CatalogRepository.NEWLY_ADDED_SERIES_CATEGORY_ID
        var list = when {
            isNewlyAdded -> sortDesktopRecentlyAdded(items)
                .take(CatalogRepository.NEWLY_ADDED_LIMIT)
            categoryId == null -> items
            else -> items.filter { it.categoryId == categoryId }
        }
        list = list.filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
        list = when (sort) {
            "ZA" -> list.sortedByDescending { it.name.lowercase() }
            "RECENT" -> sortDesktopRecentlyAdded(list)
            else -> list.sortedBy { it.name.lowercase() }
        }
        list
    }
    LaunchedEffect(pendingFocusRestore, restoreFocusId, restoreFocusIndex, restoreFocusScope, gridScope) {
        if (!pendingFocusRestore) return@LaunchedEffect
        // Wrong pane / missing id: consume immediately so sidebar is not permanently disabled.
        if (restoreFocusScope != gridScope || restoreFocusId == null) {
            onRestoreConsumed()
            return@LaunchedEffect
        }
        val id = restoreFocusId
        val idx = restoreFocusIndex.coerceAtLeast(0)
        var focused = false
        for (attempt in 0 until 8) {
            if (idx < filtered.size) {
                runCatching { gridState.scrollToItem(idx) }
            } else {
                val found = filtered.indexOfFirst { it.id == id }
                if (found >= 0) runCatching { gridState.scrollToItem(found) }
            }
            kotlinx.coroutines.yield()
            kotlinx.coroutines.delay(if (attempt == 0) 40L else 60L)
            // requestFocus() is Unit here (throws if requester inactive); do not assume Boolean.
            focused = runCatching {
                posterFocus(gridScope, id).requestFocus()
                true
            }.getOrDefault(false)
            if (focused) break
        }
        onRestoreConsumed()
    }
    Column(Modifier.fillMaxSize()) {
        PaneTitle(title)
        FilterBar(
            search = search,
            onSearch = onSearch,
            categories = categories,
            categoryId = categoryId,
            onCategory = onCategory,
            sort = sort,
            onSort = onSort
        )
        Text("${filtered.size} titles", color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp)
        Spacer(Modifier.height(TipDimens.dp(12)))
        LazyVerticalGrid(
            columns = GridCells.Fixed(AppPreferences.normalizePosterColumns(columns)),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(TipDimens.PosterRowGap),
            verticalArrangement = Arrangement.spacedBy(TipDimens.PosterRowGap),
            contentPadding = PaddingValues(bottom = TipDimens.dp(24)),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            gridItemsIndexed(filtered, key = { _, it -> it.id }) { index, item ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    DesktopPosterCard(
                        item,
                        onClick = { onClick(item, index) },
                        focusRequester = posterFocus(gridScope, item.id)
                    )
                }
            }
        }
    }
}


@Composable
fun FavoritesPane(
    favorites: List<FavoriteRef>,
    repository: CatalogRepository,
    columns: Int = 6,
    restoreFocusId: String? = null,
    restoreFocusIndex: Int = -1,
    restoreFocusScope: String? = null,
    pendingFocusRestore: Boolean = false,
    posterFocus: (scope: String, id: String) -> FocusRequester = { _, _ -> FocusRequester() },
    onRestoreConsumed: () -> Unit = {},
    onOpenDetail: (MediaItem, index: Int) -> Unit,
    onPlayFavorite: (FavoriteRef) -> Unit
) {
    val gridScope = "desk-fav"
    val gridState = rememberLazyGridState()
    // Resolve VOD/SERIES to catalog MediaItem when possible (xtream ids / artwork).
    val resolved = remember(favorites) {
        favorites.map { fav ->
            fav to (
                repository.itemById(fav.id) ?: MediaItem(
                    id = fav.id,
                    name = fav.name,
                    streamUrl = fav.streamUrl,
                    categoryId = null,
                    kind = fav.kind,
                    logoUrl = fav.logoUrl,
                    posterUrl = fav.logoUrl
                )
            )
        }
    }
    LaunchedEffect(pendingFocusRestore, restoreFocusId, restoreFocusIndex, restoreFocusScope) {
        if (!pendingFocusRestore) return@LaunchedEffect
        if (restoreFocusScope != gridScope || restoreFocusId == null) {
            onRestoreConsumed()
            return@LaunchedEffect
        }
        val id = restoreFocusId
        val idx = restoreFocusIndex.coerceAtLeast(0)
        for (attempt in 0 until 8) {
            if (idx < resolved.size) {
                runCatching { gridState.scrollToItem(idx) }
            } else {
                val found = resolved.indexOfFirst { it.first.id == id }
                if (found >= 0) runCatching { gridState.scrollToItem(found) }
            }
            kotlinx.coroutines.yield()
            kotlinx.coroutines.delay(if (attempt == 0) 40L else 60L)
            val focused = runCatching {
                posterFocus(gridScope, id).requestFocus()
                true
            }.getOrDefault(false)
            if (focused) break
        }
        onRestoreConsumed()
    }
    Column(Modifier.fillMaxSize()) {
        PaneTitle("Favorites")
        Text(
            if (favorites.isEmpty()) "Long-press a poster or Favorite while watching to save titles here."
            else "${favorites.size} saved",
            color = TipGoldMuted,
            fontSize = TipDimens.BodyMediumSp
        )
        Spacer(Modifier.height(TipDimens.dp(12)))
        if (favorites.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(TipSurface, RoundedCornerShape(TipDimens.NavCorner))
                    .padding(TipDimens.dp(16))
            ) {
                Text(
                    "No favorites yet.",
                    color = TipGoldMuted,
                    fontSize = TipDimens.BodyMediumSp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(AppPreferences.normalizePosterColumns(columns)),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(TipDimens.PosterRowGap),
                verticalArrangement = Arrangement.spacedBy(TipDimens.PosterRowGap),
                contentPadding = PaddingValues(bottom = TipDimens.dp(24)),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                gridItemsIndexed(resolved, key = { _, pair -> pair.first.id }) { index, (fav, item) ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        DesktopPosterCard(
                            item,
                            onClick = {
                                if (fav.kind == ContentKind.VOD || fav.kind == ContentKind.SERIES) {
                                    onOpenDetail(item, index)
                                } else {
                                    // LIVE (and other): play immediately like Classic favorites.
                                    onPlayFavorite(fav)
                                }
                            },
                            focusRequester = posterFocus(gridScope, fav.id)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Desktop Guide content = Classic [EpgGuideScreen] timeline (category chips, channel column,
 * program grid). Prefer calling EpgGuideScreen from DesktopAppRoot; this wrapper remains for
 * any leftover call sites.
 */
@Composable
fun GuidePane(
    channels: List<MediaItem>,
    categories: List<Category>,
    categoryId: String?,
    onCategory: (String?) -> Unit,
    repository: CatalogRepository,
    onPlay: (MediaItem) -> Unit,
    onBack: () -> Unit = {}
) {
    @Suppress("UNUSED_PARAMETER")
    val _unused = Triple(channels, categories, Unit)
    com.totaliptv.pro.ui.epg.EpgGuideScreen(
        repository = repository,
        onPlay = onPlay,
        onBack = onBack,
        initialCategoryId = categoryId,
        onCategoryChange = onCategory
    )
}

@Composable
fun DesktopSettingsPane(
    repository: CatalogRepository,
    liveCount: Int,
    movieCount: Int,
    seriesCount: Int,
    onRefresh: () -> Unit,
    onChangeSource: () -> Unit,
    refreshing: Boolean
) {
    val context = LocalContext.current
    val app = context.applicationContext as? TotalIptvProApp
    val scope = rememberCoroutineScope()
    val layout by (app?.preferences?.appLayoutMode ?: kotlinx.coroutines.flow.flowOf(AppLayoutMode.CLASSIC))
        .collectAsState(initial = AppLayoutMode.CLASSIC)
    val updateBaseUrl by (app?.preferences?.updateBaseUrl ?: kotlinx.coroutines.flow.flowOf(AppPreferences.DEFAULT_UPDATE_BASE_URL))
        .collectAsState(initial = AppPreferences.DEFAULT_UPDATE_BASE_URL)
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var pendingInstall by remember { mutableStateOf<UpdateCheckResult.Available?>(null) }
    val sources by repository.sources.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PaneTitle("Settings")
        Text(
            "Live $liveCount · Movies $movieCount · Series $seriesCount",
            color = TipGoldText,
            fontSize = TipDimens.BodyLargeSp
        )
        sources.firstOrNull()?.let { src ->
            Text("Source: ${src.name} (${src.type})", color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp)
        }
        Spacer(Modifier.height(TipDimens.dp(16)))

        val appearance by (app?.preferences?.appearanceMode ?: kotlinx.coroutines.flow.flowOf(AppearanceMode.DARK))
            .collectAsState(initial = AppearanceMode.DARK)
        val accent by (app?.preferences?.accentPreset ?: kotlinx.coroutines.flow.flowOf(AccentPreset.AMBER))
            .collectAsState(initial = AccentPreset.AMBER)
        val posterColumns by (app?.preferences?.posterColumns ?: kotlinx.coroutines.flow.flowOf(6))
            .collectAsState(initial = 6)

        SectionHeader("App layout")
        Text(
            "Classic = current TV UI. Desktop = sidebar layout.",
            color = TipGoldMuted,
            fontSize = TipDimens.BodyMediumSp
        )
        Spacer(Modifier.height(TipDimens.dp(8)))
        Row(horizontalArrangement = Arrangement.spacedBy(TipDimens.NavGap)) {
            AppLayoutMode.entries.forEach { mode ->
                TipFocusable(
                    onClick = {
                        scope.launch {
                            app?.preferences?.setAppLayoutMode(mode)
                            Toast.makeText(context, "Layout: ${mode.label}", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { focused ->
                    val sel = layout == mode
                    Text(
                        mode.label,
                        color = when {
                            sel -> TipOnAmber
                            focused -> TipAccent
                            else -> TipGoldText
                        },
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(if (sel) TipAmber else TipSurface, RoundedCornerShape(TipDimens.dp(8)))
                            .padding(horizontal = TipDimens.dp(18), vertical = TipDimens.dp(12))
                    )
                }
            }
        }

        Spacer(Modifier.height(TipDimens.dp(20)))
        SectionHeader("Theme")
        Row(horizontalArrangement = Arrangement.spacedBy(TipDimens.NavGap)) {
            AppearanceMode.entries.forEach { mode ->
                TipFocusable(
                    onClick = {
                        scope.launch {
                            app?.preferences?.setAppearanceMode(mode)
                            Toast.makeText(context, "Appearance: ${mode.label}", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { focused ->
                    val sel = appearance == mode
                    Text(
                        mode.label,
                        color = when {
                            sel -> TipOnAmber
                            focused -> TipAccent
                            else -> TipGoldText
                        },
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(if (sel) TipAmber else TipSurface, RoundedCornerShape(TipDimens.dp(8)))
                            .padding(horizontal = TipDimens.dp(18), vertical = TipDimens.dp(12))
                    )
                }
            }
        }
        Spacer(Modifier.height(TipDimens.dp(12)))
        SectionHeader("Accent color")
        Row(horizontalArrangement = Arrangement.spacedBy(TipDimens.NavGap)) {
            AccentPreset.entries.forEach { preset ->
                TipFocusable(
                    onClick = {
                        scope.launch {
                            app?.preferences?.setAccentPreset(preset)
                            Toast.makeText(context, "Accent: ${preset.label}", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { focused ->
                    val sel = accent == preset
                    Text(
                        preset.label,
                        color = when {
                            sel -> TipOnAmber
                            focused -> TipAccent
                            else -> TipGoldText
                        },
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(
                                if (sel) preset.primary else TipSurface,
                                RoundedCornerShape(TipDimens.dp(8))
                            )
                            .padding(horizontal = TipDimens.dp(14), vertical = TipDimens.dp(10))
                    )
                }
            }
        }
        Spacer(Modifier.height(TipDimens.dp(12)))
        SectionHeader("Posters per row")
        Text(
            "Home / browse grids: 5, 6, 8, or 11",
            color = TipGoldMuted,
            fontSize = TipDimens.BodyMediumSp
        )
        Spacer(Modifier.height(TipDimens.dp(8)))
        Row(horizontalArrangement = Arrangement.spacedBy(TipDimens.NavGap)) {
            AppPreferences.POSTER_COLUMN_OPTIONS.forEach { cols ->
                TipFocusable(
                    onClick = {
                        scope.launch {
                            app?.preferences?.setPosterColumns(cols)
                            Toast.makeText(context, "Posters per row: $cols", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { focused ->
                    val sel = posterColumns == cols
                    Text(
                        "$cols",
                        color = when {
                            sel -> TipOnAmber
                            focused -> TipAccent
                            else -> TipGoldText
                        },
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(if (sel) TipAmber else TipSurface, RoundedCornerShape(TipDimens.dp(8)))
                            .padding(horizontal = TipDimens.dp(18), vertical = TipDimens.dp(12))
                    )
                }
            }
        }

        Spacer(Modifier.height(TipDimens.dp(20)))
        AmberButton(if (refreshing) "Updating…" else "Update (reload catalog)", onClick = onRefresh)
        Spacer(Modifier.height(TipDimens.dp(10)))
        AmberButton("Change source", onClick = onChangeSource)

        Spacer(Modifier.height(TipDimens.dp(24)))
        SectionHeader("App updates")
        Text(
            "Installed: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            color = TipGoldMuted,
            fontSize = TipDimens.BodyMediumSp
        )
        Text("Shelf: $updateBaseUrl", color = TipGoldMuted, fontSize = TipDimens.sp(12))
        Spacer(Modifier.height(TipDimens.dp(10)))
        AmberButton(
            label = when {
                updateBusy && pendingInstall == null -> "Checking…"
                updateBusy && pendingInstall != null -> "Downloading…"
                pendingInstall != null -> "Install update ${pendingInstall?.manifest?.versionName ?: ""}"
                else -> "Check for update"
            },
            onClick = {
                if (updateBusy) return@AmberButton
                val activity = context as? Activity
                scope.launch {
                    val ready = pendingInstall
                    if (ready != null) {
                        updateBusy = true
                        updateStatus = "Downloading ${ready.manifest.versionName}…"
                        try {
                            if (activity != null && !AppUpdateChecker.canInstallPackages(context)) {
                                updateStatus = "Allow install unknown apps, then tap Install again"
                                AppUpdateChecker.openUnknownSourcesSettings(activity)
                                return@launch
                            }
                            val file = AppUpdateChecker.downloadApk(context, ready.apkUrl)
                            updateStatus = "Opening installer…"
                            AppUpdateChecker.launchInstaller(context, file)
                        } catch (t: Throwable) {
                            updateStatus = "Download failed: ${t.message ?: t.javaClass.simpleName}"
                            Toast.makeText(context, updateStatus, Toast.LENGTH_LONG).show()
                        } finally {
                            updateBusy = false
                        }
                        return@launch
                    }
                    updateBusy = true
                    updateStatus = "Checking…"
                    pendingInstall = null
                    when (val result = AppUpdateChecker.check(updateBaseUrl)) {
                        is UpdateCheckResult.UpToDate -> {
                            updateStatus = "Up to date (${BuildConfig.VERSION_NAME})"
                            Toast.makeText(context, "Up to date", Toast.LENGTH_SHORT).show()
                        }
                        is UpdateCheckResult.Available -> {
                            pendingInstall = result
                            updateStatus =
                                "Update available: ${result.manifest.versionName}. Tap to install."
                            Toast.makeText(
                                context,
                                "Update ${result.manifest.versionName} available",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is UpdateCheckResult.Failed -> {
                            updateStatus = "Check failed: ${result.message}"
                            Toast.makeText(context, updateStatus, Toast.LENGTH_LONG).show()
                        }
                    }
                    updateBusy = false
                }
            }
        )
        updateStatus?.let {
            Spacer(Modifier.height(TipDimens.dp(10)))
            Text(it, color = TipAccent, fontSize = TipDimens.BodyMediumSp)
        }

                Spacer(Modifier.height(TipDimens.dp(24)))
        SectionHeader("About")
        Text("Developed by Bill Foster", color = TipGoldText, fontSize = TipDimens.BodyLargeSp, fontWeight = FontWeight.SemiBold)
        Text("\u00A9 2026 Bill Foster. All rights reserved.", color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp)
        Spacer(Modifier.height(TipDimens.dp(8)))
        Text("Total IPTV Pro — Classic / Desktop layout", color = TipGoldMuted, fontSize = TipDimens.sp(12))
        Text("Package: com.totaliptv.pro", color = TipGoldMuted, fontSize = TipDimens.sp(12))
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = TipGoldMuted, fontSize = TipDimens.sp(12))
    }
}

@Composable
fun FilterBar(
    search: String,
    onSearch: (String) -> Unit,
    categories: List<Category>,
    categoryId: String?,
    onCategory: (String?) -> Unit,
    sort: String?,
    onSort: ((String) -> Unit)?,
    showSearch: Boolean = true
) {
    Column(Modifier.fillMaxWidth().padding(bottom = TipDimens.dp(12))) {
        if (showSearch) {
            BasicTextField(
                value = search,
                onValueChange = onSearch,
                singleLine = true,
                textStyle = TextStyle(color = TipGoldText, fontSize = TipDimens.BodyLargeSp),
                cursorBrush = SolidColor(TipAmber),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TipSurface, RoundedCornerShape(TipDimens.PosterCorner))
                    .padding(TipDimens.dp(12)),
                decorationBox = { inner ->
                    if (search.isEmpty()) Text("Search…", color = TipGoldMuted)
                    inner()
                }
            )
            Spacer(Modifier.height(TipDimens.dp(8)))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(TipDimens.NavGap)) {
            item {
                Chip("All", selected = categoryId == null, onClick = { onCategory(null) })
            }
            items(categories, key = { it.id }) { cat ->
                Chip(cat.name, selected = categoryId == cat.id, onClick = { onCategory(cat.id) })
            }
        }
        if (sort != null && onSort != null) {
            Spacer(Modifier.height(TipDimens.dp(12)))
            Row(horizontalArrangement = Arrangement.spacedBy(TipDimens.NavGap)) {
                listOf("AZ" to "A–Z", "ZA" to "Z–A", "RECENT" to "Most recent").forEach { (key, label) ->
                    Chip(label, selected = sort == key, onClick = { onSort(key) })
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    TipFocusable(onClick = onClick) { focused ->
        Text(
            label,
            color = when {
                selected -> TipOnAmber
                focused -> TipAccent
                else -> TipGoldText
            },
            fontSize = TipDimens.BodyMediumSp,
            maxLines = 1,
            modifier = Modifier
                .background(
                    when {
                        selected -> TipAmber
                        focused -> TipSurfaceAlt
                        else -> TipSurface
                    },
                    RoundedCornerShape(TipDimens.ChipCorner)
                )
                .padding(horizontal = TipDimens.ChipPadH, vertical = TipDimens.ChipPadV)
        )
    }
}
