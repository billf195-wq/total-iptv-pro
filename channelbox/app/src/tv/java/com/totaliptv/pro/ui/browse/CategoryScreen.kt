package com.totaliptv.pro.ui.browse

import android.util.Log
import android.widget.Toast

import androidx.activity.compose.BackHandler

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import com.totaliptv.pro.data.LiveChannelMapping
import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.FavoriteRef
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.local.AppPreferences
import com.totaliptv.pro.data.local.WatchProgressStore
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.data.repo.CatalogSort
import com.totaliptv.pro.ui.components.CategoryRailItem
import com.totaliptv.pro.ui.components.ChannelGridCard
import com.totaliptv.pro.ui.components.ChannelListItem
import com.totaliptv.pro.ui.components.MovieDetailSheet
import com.totaliptv.pro.ui.components.PosterCard
import com.totaliptv.pro.ui.components.SortChip
import com.totaliptv.pro.ui.components.TopBarChip
import com.totaliptv.pro.ui.theme.ClassicDimens
import com.totaliptv.pro.ui.theme.BrandBlue
import com.totaliptv.pro.ui.theme.tipScreenBrush
import com.totaliptv.pro.ui.theme.CinemaBgElevated
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseScreen(
    section: BrowseSection,
    repository: CatalogRepository,
    onPlay: (MediaItem) -> Unit,
    onPlayFromStart: (MediaItem) -> Unit = onPlay,
    onPlayFavorite: (FavoriteRef) -> Unit,
    onBack: () -> Unit,
    onOpenGuide: (() -> Unit)? = null
) {
    BackHandler { onBack() }
    val catalogRevision by repository.catalogRevision.collectAsState()
    val favorites by repository.favorites.collectAsState(initial = emptyList())
    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }
    val railFocus = remember { FocusRequester() }
    var catalogSort by remember { mutableStateOf(CatalogSort.RECENTLY_ADDED) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appPrefs = (context.applicationContext as? TotalIptvProApp)?.preferences
    val posterColumns by (appPrefs?.posterColumns ?: kotlinx.coroutines.flow.flowOf(6))
        .collectAsState(initial = 6)

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

    val categories: List<Category> = remember(section, catalogRevision) {
        when (section) {
            BrowseSection.Live -> repository.categories(ContentKind.LIVE)
            BrowseSection.Movies -> repository.categories(ContentKind.VOD)
            BrowseSection.Series -> repository.categories(ContentKind.SERIES)
            BrowseSection.Favorites -> emptyList()
        }
    }

    val categoryCounts: Map<String, Int> = remember(categories, catalogRevision) {
        categories.associate { it.id to repository.itemsForCategory(it.id).size }
    }

    var selectedCategoryId by remember(section, categories) {
        mutableStateOf(categories.firstOrNull()?.id)
    }

    val title = when (section) {
        BrowseSection.Live -> "Live TV"
        BrowseSection.Movies -> "Movies"
        BrowseSection.Series -> "Series"
        BrowseSection.Favorites -> "Favorites"
    }

    val displayItems: List<MediaItem> = remember(selectedCategoryId, section, catalogRevision, catalogSort) {
        when (section) {
            BrowseSection.Favorites -> emptyList()
            BrowseSection.Live -> {
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

    var posterOverrides by remember { mutableStateOf<Map<String, MediaItem>>(emptyMap()) }
    var detailItem by remember { mutableStateOf<MediaItem?>(null) }
    var restoreFocusId by remember { mutableStateOf<String?>(null) }
    var restoreFocusIndex by remember { mutableIntStateOf(-1) }
    var restoreFocusScope by remember { mutableStateOf<String?>(null) }
    var pendingFocusRestore by remember { mutableStateOf(false) }
    val posterFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun posterFocus(scope: String, id: String): FocusRequester =
        posterFocusRequesters.getOrPut("$scope:$id") { FocusRequester() }
    val vodGridState = rememberLazyGridState()
    val favGridState = rememberLazyGridState()
    LaunchedEffect(selectedCategoryId, catalogSort, section) {
        vodGridState.scrollToItem(0)
    }
    LaunchedEffect(detailItem, pendingFocusRestore, restoreFocusId, restoreFocusIndex, restoreFocusScope) {
        if (detailItem != null || !pendingFocusRestore) return@LaunchedEffect
        val id = restoreFocusId ?: return@LaunchedEffect
        val scope = restoreFocusScope ?: return@LaunchedEffect
        // Keep pendingFocusRestore true until requestFocus succeeds (rail cannot steal).
        val idx = restoreFocusIndex.coerceAtLeast(0)
        var focused = false
        for (attempt in 0 until 12) {
            when (scope) {
                "browse-vod" -> runCatching { vodGridState.scrollToItem(idx) }
                "browse-fav" -> runCatching { favGridState.scrollToItem(idx) }
            }
            kotlinx.coroutines.yield()
            kotlinx.coroutines.delay(if (attempt == 0) 50L else 70L)
            focused = runCatching {
                posterFocus(scope, id).requestFocus()
                true
            }.getOrDefault(false)
            if (focused) break
        }
        pendingFocusRestore = false
    }
    fun openDetail(item: MediaItem, index: Int = -1, scope: String = "browse-vod") {
        restoreFocusId = item.id
        restoreFocusIndex = index
        restoreFocusScope = scope
        pendingFocusRestore = false
        detailItem = item
    }
    fun dismissDetail(restore: Boolean) {
        detailItem = null
        pendingFocusRestore = restore && restoreFocusId != null
    }
    val vodDisplayItems: List<MediaItem> = remember(displayItems, posterOverrides) {
        if (section != BrowseSection.Movies && section != BrowseSection.Series) displayItems
        else displayItems.map { posterOverrides[it.id] ?: it }
    }

    LaunchedEffect(displayItems, section) {
        if (section != BrowseSection.Movies && section != BrowseSection.Series) {
            posterOverrides = emptyMap()
            return@LaunchedEffect
        }
        val missing = displayItems.filter { it.artworkUrl().isNullOrBlank() && it.xtreamStreamId != null }
        if (missing.isEmpty()) return@LaunchedEffect
        posterOverrides = missing.associate { it.id to repository.resolvePoster(it) }
    }

    LaunchedEffect(categories) {
        if (categories.isNotEmpty() && selectedCategoryId == null) {
            selectedCategoryId = categories.first().id
        }
        // Only auto-focus rail on category list load — not when detail dismiss / restore ends
        // (pendingFocusRestore must NOT be a LaunchedEffect key or it steals after restore).
        if (detailItem != null || pendingFocusRestore) return@LaunchedEffect
        runCatching { railFocus.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                tipScreenBrush()
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TopBarChip(label = "← Home", onClick = onBack, emphasized = true)
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = OnCinema
            )
            Spacer(Modifier.weight(1f))
            if (section == BrowseSection.Live && onOpenGuide != null) {
                TopBarChip(label = "TV Guide", onClick = onOpenGuide)
            }
            Text(
                text = when (section) {
                    BrowseSection.Favorites -> "${favorites.size} saved"
                    else -> {
                        val n = displayItems.size
                        if (section == BrowseSection.Live) "$n channels" else "$n titles"
                    }
                },
                style = MaterialTheme.typography.labelLarge,
                color = BrandBlue.copy(alpha = 0.9f)
            )
            if (section == BrowseSection.Live ||
                section == BrowseSection.Movies ||
                section == BrowseSection.Series
            ) {
                Text(
                    text = "Sort",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnCinemaMuted,
                    modifier = Modifier.padding(start = 8.dp)
                )
                SortChip(
                    label = "A–Z",
                    selected = catalogSort == CatalogSort.AZ,
                    onClick = { catalogSort = CatalogSort.AZ }
                )
                SortChip(
                    label = "Z–A",
                    selected = catalogSort == CatalogSort.ZA,
                    onClick = { catalogSort = CatalogSort.ZA }
                )
                SortChip(
                    label = "Newest",
                    selected = catalogSort == CatalogSort.RECENTLY_ADDED,
                    onClick = { catalogSort = CatalogSort.RECENTLY_ADDED }
                )
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            if (section != BrowseSection.Favorites) {
                Column(
                    modifier = Modifier
                        .width(ClassicDimens.CategoryRailWidth)
                        .fillMaxHeight()
                        .background(CinemaBgElevated)
                        .padding(vertical = 8.dp, horizontal = 10.dp)
                ) {
                    Text(
                        text = "Categories  |  ${categories.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = OnCinemaMuted,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    if (categories.isEmpty()) {
                        Text(
                            text = when (section) {
                                BrowseSection.Series ->
                                    "No series categories in this playlist yet."
                                BrowseSection.Movies ->
                                    "No movie categories loaded."
                                else -> "No categories."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnCinemaMuted,
                            modifier = Modifier.padding(12.dp)
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(categories, key = { it.id }) { cat ->
                                val selected = cat.id == selectedCategoryId
                                CategoryRailItem(
                                    title = cat.name,
                                    selected = selected,
                                    count = categoryCounts[cat.id],
                                    onClick = { selectedCategoryId = cat.id },
                                    modifier = if (cat.id == categories.first().id) {
                                        Modifier.focusRequester(railFocus)
                                    } else Modifier
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                when (section) {
                    BrowseSection.Favorites -> {
                        if (favorites.isEmpty()) {
                            EmptyBrowseMessage("No favorites yet. Long-press a movie/series poster, or Favorite while watching.")
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(AppPreferences.normalizePosterColumns(posterColumns)),
                                state = favGridState,
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                gridItemsIndexed(favorites, key = { _, it -> it.id }) { index, fav ->
                                    if (fav.kind == ContentKind.VOD || fav.kind == ContentKind.SERIES) {
                                        PosterCard(
                                            title = fav.name,
                                            imageUrl = fav.logoUrl,
                                            onClick = {
                                                // Prefer catalog item so xtreamStreamId / youtubeTrailer survive
                                                openDetail(
                                                    repository.itemById(fav.id)
                                                        ?: MediaItem(
                                                            id = fav.id,
                                                            name = fav.name,
                                                            streamUrl = fav.streamUrl,
                                                            categoryId = null,
                                                            kind = fav.kind,
                                                            logoUrl = fav.logoUrl,
                                                            posterUrl = fav.logoUrl
                                                        ),
                                                    index = index,
                                                    scope = "browse-fav"
                                                )
                                            },
                                            isFavorite = true,
                                            onLongClick = {
                                                toggleFavoriteToast(
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
                                            focusRequester = posterFocus("browse-fav", fav.id)
                                        )
                                    } else {
                                        ChannelGridCard(
                                            title = fav.name,
                                            logoUrl = fav.logoUrl,
                                            onClick = { onPlayFavorite(fav) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    BrowseSection.Live -> {
                        if (categories.isEmpty()) {
                            EmptyBrowseMessage("No live categories. Check your playlist or reload from Home.")
                        } else if (displayItems.isEmpty()) {
                            EmptyBrowseMessage("No channels in this category.")
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(8.dp, bottom = 28.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(displayItems, key = { it.id }) { item ->
                                    ChannelListItem(
                                        title = item.name,
                                        logoUrl = item.logoUrl,
                                        onClick = {
                                            Log.i(
                                                "TotalIPTV.Live",
                                                "browseClick name=${item.name} id=${item.id} sid=${item.xtreamStreamId} num=${item.channelNum} url=${item.streamUrl}"
                                            )
                                            onPlay(item)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    BrowseSection.Movies, BrowseSection.Series -> {
                        if (categories.isEmpty()) {
                            EmptyBrowseMessage(
                                if (section == BrowseSection.Series)
                                    "Series catalogs aren't available for this playlist."
                                else
                                    "Movies are still loading, or this playlist has no VOD."
                            )
                        } else if (displayItems.isEmpty()) {
                            EmptyBrowseMessage("No titles in this category.")
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 148.dp),
                                state = vodGridState,
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                gridItemsIndexed(vodDisplayItems, key = { _, it -> it.id }) { index, item ->
                                    PosterCard(
                                        title = item.name,
                                        imageUrl = item.artworkUrl(),
                                        onClick = { openDetail(item, index, "browse-vod") },
                                        isFavorite = item.id in favoriteIds,
                                        onLongClick = { toggleFavoriteToast(item) },
                                        rating = item.displayRating(),
                                        focusRequester = posterFocus("browse-vod", item.id)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    detailItem?.let { detail ->
        val app = context.applicationContext as? TotalIptvProApp
        val store = app?.watchProgress ?: WatchProgressStore(context.applicationContext)
        val detailProgress = runCatching { store.forCatalogItem(detail.id) }.getOrNull()
        MovieDetailSheet(
            item = detail,
            isFavorite = detail.id in favoriteIds,
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
                // Prefer saved leaf (episode) when present so series resumes correctly.
                val playTarget = detailProgress?.toMediaItem() ?: detail
                dismissDetail(restore = false)
                onPlay(playTarget)
            },
            onPlayFromStart = {
                // Restart: clear progress + EXTRA_START_OVER via onPlayFromStart.
                runCatching {
                    val existing = store.forCatalogItem(detail.id)
                    if (existing != null) store.clear(existing.id)
                    store.clear(detail.id)
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmptyBrowseMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = OnCinemaMuted,
            modifier = Modifier.padding(32.dp)
        )
    }
}

/** Legacy single-category screen kept for compatibility. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CategoryScreen(
    category: Category,
    repository: CatalogRepository,
    onPlay: (MediaItem) -> Unit,
    onPlayFromStart: (MediaItem) -> Unit = onPlay,
    onBack: () -> Unit
) {
    val section = when (category.kind) {
        ContentKind.LIVE -> BrowseSection.Live
        ContentKind.VOD -> BrowseSection.Movies
        ContentKind.SERIES -> BrowseSection.Series
    }
    BrowseScreen(
        section = section,
        repository = repository,
        onPlay = onPlay,
        onPlayFromStart = onPlayFromStart,
        onPlayFavorite = { fav ->
            onPlay(
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
        onBack = onBack
    )
}
