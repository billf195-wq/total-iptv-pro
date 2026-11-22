package com.totaliptv.pro.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.data.LiveChannelMapping
import com.totaliptv.pro.data.LiveFavorites
import com.totaliptv.pro.data.FavoriteSections
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.data.repo.CatalogSort
import com.totaliptv.pro.ui.components.PhoneDetailSheet
import com.totaliptv.pro.ui.components.PhonePageHeader
import com.totaliptv.pro.ui.components.PhoneLiveRow
import com.totaliptv.pro.ui.components.PhonePosterCard
import com.totaliptv.pro.ui.theme.BrandBlue
import com.totaliptv.pro.ui.theme.CinemaSurface
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import com.totaliptv.pro.ui.theme.tipScreenBrush
import com.totaliptv.pro.dvr.DvrActions
import com.totaliptv.pro.dvr.DvrRecordUi
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.local.LastPlace
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PhoneBrowseScreen(
    section: BrowseSection,
    repository: CatalogRepository,
    onPlay: (MediaItem) -> Unit,
    onPlayFromStart: (MediaItem) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val revision by repository.catalogRevision.collectAsState()
    val vodLoading by repository.vodLoading.collectAsState()
    val vodError by repository.vodError.collectAsState()
    val isVodSection = section == BrowseSection.Movies || section == BrowseSection.Series
    var loading by remember { mutableStateOf(true) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favorites by repository.favorites.collectAsState(initial = emptyList())
    val lastPlace = remember { (context.applicationContext as TotalIptvProApp).lastPlace }
    val initialPlace = remember { lastPlace.peek() }
    val sectionScreen = when (section) {
        BrowseSection.Live -> LastPlace.SCREEN_LIVE
        BrowseSection.Movies -> LastPlace.SCREEN_MOVIES
        BrowseSection.Series -> LastPlace.SCREEN_SERIES
        else -> null
    }
    var selectedCat by remember {
        mutableStateOf(
            initialPlace?.takeIf { it.screen == sectionScreen }?.categoryId
        )
    }
    var sort by remember { mutableStateOf(CatalogSort.RECENTLY_ADDED) }
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var detail by remember { mutableStateOf<MediaItem?>(null) }
    val restorePlace = initialPlace?.takeIf { it.screen == sectionScreen }
    val restoreIndex = restorePlace?.firstVisibleIndex ?: 0
    val restoreOffset = restorePlace?.firstVisibleOffset ?: 0
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = restoreIndex,
        initialFirstVisibleItemScrollOffset = restoreOffset
    )
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = restoreIndex,
        initialFirstVisibleItemScrollOffset = restoreOffset
    )
    var lastListKey by remember { mutableStateOf("") }
    var pendingScrollTop by remember { mutableStateOf(false) }
    var pendingRestoreScroll by remember {
        mutableStateOf(restorePlace != null && (restoreIndex > 0 || restoreOffset > 0))
    }
    val dvrSnap by remember(context) {
        (context.applicationContext as TotalIptvProApp).dvr.snapshot
    }.collectAsState()
    val kind = when (section) {
        BrowseSection.Live -> ContentKind.LIVE
        BrowseSection.Movies -> ContentKind.VOD
        BrowseSection.Series -> ContentKind.SERIES
        BrowseSection.Favorites -> null
    }

    LaunchedEffect(section, revision) {
        loading = true
        withContext(Dispatchers.IO) { runCatching { repository.ensureCatalogLoaded() } }
        if (section == BrowseSection.Favorites) {
            categories = emptyList()
            selectedCat = null
        } else {
            val raw = repository.categories(kind)
            categories = if (section == BrowseSection.Live) LiveFavorites.withFavoritesCategory(raw) else raw
            if (selectedCat == null || categories.none { it.id == selectedCat }) {
                selectedCat = if (section == BrowseSection.Live) {
                    LiveFavorites.defaultCategoryId(categories, LiveFavorites.liveFavoriteCount(favorites))
                } else {
                    categories.firstOrNull()?.id
                }
            }
        }
        loading = false
    }

    LaunchedEffect(section, selectedCat, sort, revision, favorites) {
        items = when (section) {
            BrowseSection.Favorites -> emptyList() // filled below via flow
            BrowseSection.Live -> {
                val aligned = LiveFavorites.filterLive(
                    repository.liveItems(),
                    favorites,
                    selectedCat
                )
                // Live has no ratings: Top rated keeps the provider lineup order.
                if (sort == CatalogSort.RECENTLY_ADDED || sort == CatalogSort.TOP_RATED) aligned
                else repository.sortCatalogItems(aligned, sort)
            }
            else -> {
                val raw = selectedCat?.let { repository.itemsForCategory(it) }.orEmpty()
                repository.sortCategoryItems(selectedCat, raw, sort)
            }
        }
        // New group or sort: start at the top. (A catalog refresh keeps the scroll spot.)
        val key = "${section.name}|$selectedCat|${sort.name}"
        if (key != lastListKey) {
            lastListKey = key
            if (!pendingRestoreScroll) pendingScrollTop = true
        }
    }
    // Scroll after the new list is composed; scrolling earlier lets the grid keep the old
    // first item (by key) in view, which lands mid-list after a re-sort.
    LaunchedEffect(items, pendingScrollTop, pendingRestoreScroll) {
        if (pendingRestoreScroll) {
            val idx = restoreIndex
            if ((idx > 0 || restoreOffset > 0) && items.isNotEmpty()) {
                val safe = idx.coerceAtMost(items.lastIndex).coerceAtLeast(0)
                runCatching { gridState.scrollToItem(safe, restoreOffset) }
                runCatching { listState.scrollToItem(safe, restoreOffset) }
            }
            pendingRestoreScroll = false
            pendingScrollTop = false
            return@LaunchedEffect
        }
        if (!pendingScrollTop) return@LaunchedEffect
        runCatching { gridState.scrollToItem(0) }
        runCatching { listState.scrollToItem(0) }
        pendingScrollTop = false
    }
    LaunchedEffect(sectionScreen, selectedCat, listState) {
        val screen = sectionScreen ?: return@LaunchedEffect
        snapshotFlow {
            Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, selectedCat)
        }.collect { (idx, off, cat) ->
            lastPlace.saveBrowse(
                screen = screen,
                categoryId = cat,
                firstVisibleIndex = idx,
                firstVisibleOffset = off
            )
        }
    }
    LaunchedEffect(sectionScreen, selectedCat, gridState) {
        val screen = sectionScreen ?: return@LaunchedEffect
        snapshotFlow {
            Triple(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset, selectedCat)
        }.collect { (idx, off, cat) ->
            lastPlace.saveBrowse(
                screen = screen,
                categoryId = cat,
                firstVisibleIndex = idx,
                firstVisibleOffset = off
            )
        }
    }

    val favItems = remember(favorites, revision) {
        favorites.map {
            MediaItem(
                id = it.id,
                name = it.name,
                streamUrl = it.streamUrl,
                categoryId = null,
                kind = it.kind,
                logoUrl = it.logoUrl
            )
        }
    }
    val displayItems = if (section == BrowseSection.Favorites) favItems else items

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tipScreenBrush())
            .padding(contentPadding)
    ) {
        PhonePageHeader()

        if (section != BrowseSection.Favorites) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories, key = { it.id }) { cat ->
                    FilterChip(
                        selected = cat.id == selectedCat,
                        onClick = { selectedCat = cat.id },
                        label = {
                            Text(
                                cat.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val modes = buildList {
                    add(CatalogSort.RECENTLY_ADDED to "Newest")
                    add(CatalogSort.AZ to "A–Z")
                    add(CatalogSort.ZA to "Z–A")
                    if (isVodSection) add(CatalogSort.TOP_RATED to "Top rated")
                }
                items(modes, key = { it.first.name }) { (mode, label) ->
                    AssistChip(
                        onClick = { sort = mode },
                        label = { Text(label) },
                        leadingIcon = if (sort == mode) {
                            { Text("•", color = BrandBlue) }
                        } else null
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        when {
            loading && displayItems.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            displayItems.isEmpty() && isVodSection && vodLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(if (section == BrowseSection.Series) "Loading series…" else "Loading movies…", color = OnCinemaMuted)
                    }
                }
            }
            displayItems.isEmpty() && isVodSection && vodError != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(vodError ?: "", color = OnCinemaMuted)
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.Button(onClick = { repository.retryVod() }) { Text("Retry") }
                    }
                }
            }
            displayItems.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (section == BrowseSection.Favorites)
                            "No favorites yet. Press and hold a Live channel, or Favorite a movie or series."
                        else if (section == BrowseSection.Live && LiveFavorites.isFavoritesCategory(selectedCat))
                            "No favorite channels yet. Press and hold a channel to add it here."
                        else
                            "Nothing here yet",
                        color = OnCinemaMuted
                    )
                }
            }
            section == BrowseSection.Favorites -> {
                val liveFavs = displayItems.filter { it.kind == ContentKind.LIVE }
                val movieFavs = displayItems.filter { it.kind == ContentKind.VOD }
                val seriesFavs = displayItems.filter { it.kind == ContentKind.SERIES }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    if (liveFavs.isNotEmpty()) {
                        item(key = "ph-fav-h-Live") {
                            Text("Live", style = MaterialTheme.typography.titleMedium, color = OnCinema, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                        }
                        items(liveFavs, key = { it.id }) { fav ->
                            PhoneLiveRow(
                                item = fav,
                                onClick = { onPlay(fav) },
                                isFavorite = true,
                                onLongClick = {
                                    val wasFav = fav.id in favorites.map { it.id }.toSet()
                                    scope.launch {
                                        repository.toggleFavorite(fav)
                                        Toast.makeText(
                                            context,
                                            if (wasFav) "Removed from favorites" else "Added to favorites",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )
                        }
                    }
                    if (movieFavs.isNotEmpty()) {
                        item(key = "ph-fav-h-Movies") {
                            Text("Movies", style = MaterialTheme.typography.titleMedium, color = OnCinema, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                        }
                        items(movieFavs, key = { it.id }) { fav ->
                            PhonePosterCard(item = fav, onClick = { detail = fav })
                        }
                    }
                    if (seriesFavs.isNotEmpty()) {
                        item(key = "ph-fav-h-Series") {
                            Text("Series", style = MaterialTheme.typography.titleMedium, color = OnCinema, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                        }
                        items(seriesFavs, key = { it.id }) { fav ->
                            PhonePosterCard(item = fav, onClick = { detail = fav })
                        }
                    }
                }
            }
            section == BrowseSection.Live -> {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(displayItems, key = { it.id }) { item ->
                        PhoneLiveRow(
                            item = item,
                            onClick = {
                                if (item.kind == ContentKind.LIVE) onPlay(item) else detail = item
                            },
                            onLongClick = if (item.kind == ContentKind.LIVE) {
                                {
                                    val wasFav = item.id in favorites.map { it.id }.toSet()
                                    scope.launch {
                                        repository.toggleFavorite(item)
                                        Toast.makeText(
                                            context,
                                            if (wasFav) "Removed from favorites" else "Added to favorites",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            } else null,
                            isFavorite = item.kind == ContentKind.LIVE && item.id in favorites.map { it.id }.toSet(),
                            onRecord = if (item.kind == ContentKind.LIVE || item.streamUrl.isNotBlank()) {
                                { DvrActions.recordNow(context, item) }
                            } else null,
                            recordActive = DvrRecordUi.matches(dvrSnap.active, item.id, item.streamUrl),
                            recordLabel = DvrRecordUi.appearance(dvrSnap.active, item.id, item.streamUrl).label
                        )
                    }
                }
            }
            else -> {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(120.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    gridItems(displayItems, key = { it.id }) { item ->
                        PhonePosterCard(
                            item = item,
                            onClick = { detail = item },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    detail?.let { item ->
        PhoneDetailSheet(
            item = item,
            repository = repository,
            onDismiss = { detail = null },
            onPlay = onPlay,
            onPlayFromStart = onPlayFromStart
        )
    }
}
