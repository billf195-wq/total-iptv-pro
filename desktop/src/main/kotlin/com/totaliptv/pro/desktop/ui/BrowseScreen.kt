package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.desktop.data.Catalog
import com.totaliptv.pro.desktop.data.Category
import com.totaliptv.pro.desktop.data.ChannelEpg
import com.totaliptv.pro.desktop.data.ContentKind
import com.totaliptv.pro.desktop.data.LiveChannelMapping
import com.totaliptv.pro.desktop.data.SeriesPlayback
import com.totaliptv.pro.desktop.data.FavoritesStore
import com.totaliptv.pro.desktop.data.MediaItem
import com.totaliptv.pro.desktop.data.SavedPrefs
import com.totaliptv.pro.desktop.data.ResumeStore
import com.totaliptv.pro.desktop.data.SeriesDetail
import com.totaliptv.pro.desktop.data.VodDetail

enum class MainNav {
    HOME, LIVE, MOVIES, SERIES, GUIDE, FAVORITES, SETTINGS
}

/** Movies / Series browse sort (persisted as prefs.browseSort). */
enum class BrowseSort(val prefsValue: String, val label: String) {
    AZ("AZ", "A–Z"),
    ZA("ZA", "Z–A"),
    RECENT("RECENT", "Most recent");

    companion object {
        fun fromPrefs(value: String?): BrowseSort =
            entries.firstOrNull { it.prefsValue.equals(value, ignoreCase = true) } ?: AZ
    }
}

@Composable
fun BrowseScreen(
    catalog: Catalog,
    prefs: SavedPrefs,
    playingTitle: String?,
    refreshing: Boolean,
    statusMessage: String?,
    seriesDetail: SeriesDetail?,
    seriesLoading: Boolean,
    seriesError: String?,
    vodDetail: VodDetail?,
    vodLoading: Boolean,
    vodError: String?,
    epgByStreamId: Map<Int, ChannelEpg>,
    epgLoadingIds: Set<Int>,
    resumeEntries: List<ResumeStore.ResumeEntry>,
    favoriteEntries: List<FavoritesStore.FavoriteEntry>,
    onPlay: (MediaItem) -> Unit,
    onOpenSeries: (MediaItem) -> Unit,
    onCloseSeries: () -> Unit,
    onOpenVod: (MediaItem) -> Unit,
    onCloseVod: () -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onSavePrefs: (SavedPrefs) -> Unit,
    onNeedEpg: (MediaItem) -> Unit,
    onResumeEntry: (ResumeStore.ResumeEntry, MediaItem?) -> Unit,
    playingSeriesId: Int? = null,
    playingSeason: Int? = null,
    playingEpisodeNum: Int? = null,
    playingEpisodeId: String? = null,
    playingStreamUrl: String? = null
) {
    var nav by remember { mutableStateOf(MainNav.HOME) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val banner = rememberBannerBitmap()
    val favoriteKeys = remember(favoriteEntries) { favoriteEntries.map { it.key }.toSet() }

    if (seriesDetail != null || seriesLoading) {
        val seriesMedia = remember(seriesDetail, catalog) {
            seriesDetail?.let { d ->
                catalog.seriesItems.find { it.xtreamStreamId == d.seriesId }
                    ?: MediaItem(
                        id = "series-${d.seriesId}",
                        name = d.name,
                        streamUrl = "",
                        categoryId = null,
                        kind = ContentKind.SERIES,
                        posterUrl = d.posterUrl,
                        backdropUrl = d.backdropUrl,
                        xtreamStreamId = d.seriesId,
                        playable = false,
                        plot = d.plot,
                        cast = d.cast,
                        rating = d.rating,
                        year = d.year,
                        genre = d.genre
                    )
            }
        }
        Column(Modifier.fillMaxSize().background(TipBg)) {
            TopBanner(banner)
            val resume = ResumeStore.forSeries(seriesDetail?.seriesId, resumeEntries)
            SeriesDetailPane(
                detail = seriesDetail,
                loading = seriesLoading,
                error = seriesError,
                playingTitle = playingTitle,
                isFavorite = seriesMedia?.let { favoriteKeys.contains(it.id) } == true,
                resumeSeason = resume?.season,
                resumeEpisodeNum = resume?.episodeNum,
                resumeEpisodeId = resume?.episodeId,
                playingThisSeries = playingSeriesId != null && playingSeriesId == seriesDetail?.seriesId,
                playingSeason = playingSeason,
                playingEpisodeNum = playingEpisodeNum,
                playingEpisodeId = playingEpisodeId,
                playingStreamUrl = playingStreamUrl,
                onToggleFavorite = { seriesMedia?.let(onToggleFavorite) },
                onPlayEpisode = onPlay,
                onBack = onCloseSeries,
                onStop = onStop,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
        return
    }

    if (vodDetail != null || vodLoading) {
        val vodMedia = remember(vodDetail) { vodDetail?.toMediaItem() }
        Column(Modifier.fillMaxSize().background(TipBg)) {
            TopBanner(banner)
            VodDetailPane(
                detail = vodDetail,
                loading = vodLoading,
                error = vodError,
                playingTitle = playingTitle,
                isFavorite = vodMedia?.let { favoriteKeys.contains(it.id) } == true,
                onToggleFavorite = { vodMedia?.let(onToggleFavorite) },
                onPlay = { vodMedia?.let(onPlay) },
                onBack = onCloseVod,
                onStop = onStop,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
        return
    }

    val section = when (nav) {
        MainNav.LIVE -> ContentKind.LIVE
        MainNav.MOVIES -> ContentKind.VOD
        MainNav.SERIES -> ContentKind.SERIES
        else -> null
    }

    Column(Modifier.fillMaxSize().background(TipBg)) {
        TopBanner(banner)

        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .background(TipSurface)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("TOTAL IPTV PRO", color = TipBlue, fontWeight = FontWeight.Bold)
                Text("Desktop", style = MaterialTheme.typography.bodyMedium, color = TipMuted)
                Spacer(Modifier.height(12.dp))
                NavBtn("Home", Icons.Default.Home, nav == MainNav.HOME) {
                    nav = MainNav.HOME
                    selectedCategoryId = null
                    query = ""
                }
                NavBtn("Live TV", Icons.Default.LiveTv, nav == MainNav.LIVE) {
                    val stayOnLiveFamily = nav == MainNav.LIVE || nav == MainNav.GUIDE
                    nav = MainNav.LIVE
                    if (!stayOnLiveFamily) {
                        selectedCategoryId = null
                        query = ""
                    }
                }
                NavBtn("Movies", Icons.Default.Movie, nav == MainNav.MOVIES) {
                    nav = MainNav.MOVIES
                    selectedCategoryId = null
                    query = ""
                }
                NavBtn("Series", Icons.Default.Tv, nav == MainNav.SERIES) {
                    nav = MainNav.SERIES
                    selectedCategoryId = null
                    query = ""
                }
                NavBtn("TV Guide", Icons.Default.Schedule, nav == MainNav.GUIDE) {
                    val stayOnLiveFamily = nav == MainNav.LIVE || nav == MainNav.GUIDE
                    nav = MainNav.GUIDE
                    if (!stayOnLiveFamily) {
                        selectedCategoryId = null
                        query = ""
                    }
                }
                NavBtn("Favorites", Icons.Default.Favorite, nav == MainNav.FAVORITES) {
                    nav = MainNav.FAVORITES
                    selectedCategoryId = null
                    query = ""
                }
                NavBtn("Settings", Icons.Default.Settings, nav == MainNav.SETTINGS) {
                    nav = MainNav.SETTINGS
                }
                Spacer(Modifier.weight(1f))
                if (playingTitle != null) {
                    Text("Playing", style = MaterialTheme.typography.bodyMedium)
                    Text(playingTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = TipAccent)
                    TextButton(onClick = onStop) { Text("Stop player") }
                }
                Button(
                    onClick = onRefresh,
                    enabled = !refreshing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TipBlue,
                        contentColor = TipOnAmber,
                        disabledContainerColor = TipSurfaceAlt,
                        disabledContentColor = TipMuted
                    )
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = TipOnAmber)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp), tint = TipOnAmber)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Update", color = TipOnAmber, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onLogout) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(16.dp), tint = TipOnBg)
                    Spacer(Modifier.width(6.dp))
                    Text("Change source", color = TipOnBg, fontWeight = FontWeight.Medium)
                }
            }

            when (nav) {
                MainNav.HOME -> {
                    HomeScreen(
                        catalog = catalog,
                        resumeEntries = resumeEntries,
                        onOpenVod = onOpenVod,
                        onOpenSeries = onOpenSeries,
                        onResumeEntry = onResumeEntry,
                        posterColumns = prefs.posterColumns.let { if (it in setOf(5, 6, 8, 11)) it else 11 },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
                MainNav.GUIDE -> {
                    GuideScreen(
                        channels = catalog.liveItems,
                        categories = catalog.liveCategories,
                        epgByStreamId = epgByStreamId,
                        epgLoadingIds = epgLoadingIds,
                        playingTitle = playingTitle,
                        guideStyle = prefs.guideStyle,
                        selectedCategoryId = selectedCategoryId,
                        onCategoryChange = { selectedCategoryId = it },
                        query = query,
                        onQueryChange = { query = it },
                        onNeedEpg = onNeedEpg,
                        onPlayChannel = onPlay,
                        onStop = onStop
                    )
                }
                MainNav.SETTINGS -> {
                    SettingsScreen(
                        prefs = prefs,
                        playingTitle = playingTitle,
                        onSavePrefs = onSavePrefs,
                        onChangeSource = onLogout,
                        onStop = onStop
                    )
                }
                MainNav.FAVORITES -> {
                    FavoritesPane(
                        catalog = catalog,
                        favoriteEntries = favoriteEntries,
                        query = query,
                        onQueryChange = { query = it },
                        onOpenVod = onOpenVod,
                        onOpenSeries = onOpenSeries,
                        onPlayLive = onPlay,
                        onToggleFavorite = onToggleFavorite,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
                MainNav.LIVE, MainNav.MOVIES, MainNav.SERIES -> {
                    val kind = section!!
                    BrowseContentPane(
                        kind = kind,
                        catalog = catalog,
                        posterColumns = prefs.posterColumns.let { if (it in setOf(5, 6, 8, 11)) it else 6 },
                        browseSort = BrowseSort.fromPrefs(prefs.browseSort),
                        onBrowseSortChange = { sort ->
                            onSavePrefs(prefs.copy(browseSort = sort.prefsValue))
                        },
                        selectedCategoryId = selectedCategoryId,
                        onCategoryChange = { selectedCategoryId = it },
                        query = query,
                        onQueryChange = { query = it },
                        statusMessage = statusMessage,
                        favoriteKeys = favoriteKeys,
                        onPlay = onPlay,
                        onOpenSeries = onOpenSeries,
                        onOpenVod = onOpenVod,
                        onToggleFavorite = onToggleFavorite
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberBannerBitmap(): ImageBitmap? = remember {
    runCatching {
        useResource("app_banner.png") { loadImageBitmap(it) }
    }.getOrNull()
}

@Composable
private fun TopBanner(banner: ImageBitmap?) {
    if (banner == null) return
    // Logo sits top-left (not centered across the full chrome width).
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp)
            .background(TipSurface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Image(
            bitmap = banner,
            contentDescription = "Total IPTV Pro",
            modifier = Modifier
                .fillMaxHeight()
                .wrapContentWidth(),
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart
        )
    }
}

@Composable
private fun BrowseContentPane(
    kind: ContentKind,
    catalog: Catalog,
    posterColumns: Int,
    browseSort: BrowseSort,
    onBrowseSortChange: (BrowseSort) -> Unit,
    selectedCategoryId: String?,
    onCategoryChange: (String?) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    statusMessage: String?,
    favoriteKeys: Set<String>,
    onPlay: (MediaItem) -> Unit,
    onOpenSeries: (MediaItem) -> Unit,
    onOpenVod: (MediaItem) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit
) {
    val categories = when (kind) {
        ContentKind.LIVE -> catalog.liveCategories
        ContentKind.VOD -> catalog.vodCategories
        ContentKind.SERIES -> catalog.seriesCategories
    }
    val allItems = when (kind) {
        ContentKind.LIVE -> catalog.liveItems
        ContentKind.VOD -> catalog.vodItems
        ContentKind.SERIES -> catalog.seriesItems
    }
    val filtered = remember(kind, selectedCategoryId, query, catalog, browseSort) {
        if (kind == ContentKind.LIVE) {
            LiveChannelMapping.filterLiveChannels(allItems, selectedCategoryId, query)
        } else {
            val base = allItems.asSequence()
                .filter { selectedCategoryId == null || it.categoryId == selectedCategoryId }
                .filter {
                    query.isBlank() || it.name.contains(query, ignoreCase = true) ||
                        (it.groupTitle?.contains(query, ignoreCase = true) == true)
                }
                .toList()
            when (browseSort) {
                BrowseSort.AZ -> base.sortedBy { it.name.lowercase() }
                BrowseSort.ZA -> base.sortedByDescending { it.name.lowercase() }
                BrowseSort.RECENT -> base.sortedWith(
                    compareByDescending<MediaItem> { it.addedEpoch }
                        .thenByDescending { it.xtreamStreamId ?: 0 }
                        .thenBy { it.name.lowercase() }
                )
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (kind) {
                    ContentKind.LIVE -> "Live TV"
                    ContentKind.VOD -> "Movies"
                    ContentKind.SERIES -> "Series"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = TipOnBg,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${filtered.size} / ${allItems.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = TipMuted
            )
        }
        if (!statusMessage.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(statusMessage, color = TipAccent, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = {
                Text(
                    when (kind) {
                        ContentKind.LIVE -> "Search channels…"
                        ContentKind.VOD -> "Search movies…"
                        ContentKind.SERIES -> "Search series…"
                    }
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TipBlue,
                unfocusedBorderColor = TipSurfaceAlt,
                focusedContainerColor = TipSurface,
                unfocusedContainerColor = TipSurface,
                focusedTextColor = TipOnBg,
                unfocusedTextColor = TipOnBg,
                cursorColor = TipBlue,
                focusedPlaceholderColor = TipMuted,
                unfocusedPlaceholderColor = TipMuted,
                focusedLeadingIconColor = TipBlue,
                unfocusedLeadingIconColor = TipMuted
            )
        )
        Spacer(Modifier.height(12.dp))

        if (kind != ContentKind.LIVE) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Sort",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TipMuted
                )
                BrowseSort.entries.forEach { sort ->
                    FilterChip(
                        selected = browseSort == sort,
                        onClick = { onBrowseSortChange(sort) },
                        label = { Text(sort.label) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (categories.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedCategoryId == null,
                        onClick = { onCategoryChange(null) },
                        label = { Text("All") }
                    )
                }
                items(categories, key = { it.id }) { cat: Category ->
                    FilterChip(
                        selected = selectedCategoryId == cat.id,
                        onClick = { onCategoryChange(cat.id) },
                        label = { Text(cat.name, maxLines = 1) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        catalog.warnings.forEach {
            Text(it, color = TipAccent, style = MaterialTheme.typography.bodyMedium)
        }

        if (kind == ContentKind.LIVE) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered, key = { it.id }) { item ->
                    MediaRow(
                        item = item,
                        isFavorite = favoriteKeys.contains(item.id),
                        onClick = { onPlay(item) },
                        onToggleFavorite = { onToggleFavorite(item) }
                    )
                }
            }
        } else {
            // Column count from Settings (5 / 6 / 11) — posters size to the cell automatically.
            LazyVerticalGrid(
                columns = GridCells.Fixed(posterColumns.coerceIn(5, 11)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filtered, key = { it.id }) { item ->
                    PosterCard(
                        item = item,
                        isFavorite = favoriteKeys.contains(item.id),
                        onClick = {
                            when {
                                item.kind == ContentKind.SERIES && !item.playable -> onOpenSeries(item)
                                item.kind == ContentKind.VOD -> onOpenVod(item)
                                else -> onPlay(item)
                            }
                        },
                        onToggleFavorite = { onToggleFavorite(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoritesPane(
    catalog: Catalog,
    favoriteEntries: List<FavoritesStore.FavoriteEntry>,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenVod: (MediaItem) -> Unit,
    onOpenSeries: (MediaItem) -> Unit,
    onPlayLive: (MediaItem) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val resolved = remember(favoriteEntries, catalog, query) {
        favoriteEntries.mapNotNull { entry ->
            val media = when (entry.kind) {
                ContentKind.VOD.name -> catalog.vodItems.find { it.id == entry.catalogId }
                    ?: catalog.vodItems.find { it.xtreamStreamId == entry.xtreamStreamId }
                ContentKind.SERIES.name -> catalog.seriesItems.find { it.id == entry.catalogId }
                    ?: catalog.seriesItems.find { it.xtreamStreamId == entry.xtreamStreamId }
                ContentKind.LIVE.name -> catalog.liveItems.find { it.id == entry.catalogId }
                    ?: catalog.liveItems.find { it.xtreamStreamId == entry.xtreamStreamId }
                else -> null
            } ?: MediaItem(
                id = entry.catalogId,
                name = entry.name,
                streamUrl = entry.streamUrl,
                categoryId = entry.categoryId,
                kind = runCatching { ContentKind.valueOf(entry.kind) }.getOrDefault(ContentKind.VOD),
                posterUrl = entry.posterUrl,
                xtreamStreamId = entry.xtreamStreamId,
                playable = entry.kind != ContentKind.SERIES.name || entry.streamUrl.isNotBlank()
            )
            if (query.isNotBlank() && !media.name.contains(query, ignoreCase = true)) null
            else entry to media
        }
    }

    Column(modifier.fillMaxSize().padding(20.dp)) {
        Text("Favorites", style = MaterialTheme.typography.headlineMedium, color = TipOnBg)
        Text(
            "Movies and series you’ve marked with a heart",
            style = MaterialTheme.typography.bodyMedium,
            color = TipMuted
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Search favorites…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TipBlue,
                unfocusedBorderColor = TipSurfaceAlt,
                focusedContainerColor = TipSurface,
                unfocusedContainerColor = TipSurface,
                focusedTextColor = TipOnBg,
                unfocusedTextColor = TipOnBg,
                cursorColor = TipBlue,
                focusedPlaceholderColor = TipMuted,
                unfocusedPlaceholderColor = TipMuted,
                focusedLeadingIconColor = TipBlue,
                unfocusedLeadingIconColor = TipMuted
            )
        )
        Spacer(Modifier.height(12.dp))
        if (resolved.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(TipSurface)
                    .padding(16.dp)
            ) {
                Text(
                    "No favorites yet. Tap the heart on a movie or series poster.",
                    color = TipMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(resolved, key = { it.first.key }) { (_, item) ->
                    PosterCard(
                        item = item,
                        isFavorite = true,
                        onClick = {
                            when (item.kind) {
                                ContentKind.SERIES -> onOpenSeries(item)
                                ContentKind.VOD -> onOpenVod(item)
                                ContentKind.LIVE -> onPlayLive(item)
                            }
                        },
                        onToggleFavorite = { onToggleFavorite(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterCard(
    item: MediaItem,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TipSurface)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth()) {
            RemoteArtwork(
                url = item.artworkUrl(),
                contentDescription = item.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp)),
                fallbackIcon = when (item.kind) {
                    ContentKind.VOD -> Icons.Default.Movie
                    ContentKind.SERIES -> Icons.Default.Tv
                    ContentKind.LIVE -> Icons.Default.LiveTv
                },
                contentScale = ContentScale.Crop
            )
            RatingBadge(
                score = item.ratingScore(),
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
            )
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(36.dp)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove favorite" else "Add favorite",
                    tint = if (isFavorite) TipAccent else TipOnBg.copy(alpha = 0.9f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = TipOnBg,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun VodDetailPane(
    detail: VodDetail?,
    loading: Boolean,
    error: String?,
    playingTitle: String?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
    onBack: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TipOnBg)
            }
            Text(
                detail?.name ?: "Movie",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (playingTitle != null) {
                TextButton(onClick = onStop) { Text("Stop player") }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading && detail == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TipBlue)
                }
            }
            error != null && detail == null -> {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onBack) { Text("Back") }
            }
            detail != null -> {
                Row(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    RemoteArtwork(
                        url = detail.posterUrl ?: detail.backdropUrl,
                        contentDescription = detail.name,
                        modifier = Modifier
                            .width(220.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(12.dp)),
                        fallbackIcon = Icons.Default.Movie,
                        contentScale = ContentScale.Crop
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            detail.name,
                            style = MaterialTheme.typography.headlineMedium,
                            color = TipOnBg
                        )
                        Spacer(Modifier.height(8.dp))
                        val meta = listOfNotNull(
                            detail.year?.toString(),
                            detail.rating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
                            detail.genre?.takeIf { it.isNotBlank() }
                        ).joinToString("  ·  ")
                        if (meta.isNotBlank()) {
                            Text(meta, style = MaterialTheme.typography.bodyLarge, color = TipMuted)
                            Spacer(Modifier.height(12.dp))
                        }
                        Text("Plot", style = MaterialTheme.typography.titleMedium, color = TipBlue)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            detail.plot?.takeIf { it.isNotBlank() } ?: "No description available.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TipOnBg
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Cast", style = MaterialTheme.typography.titleMedium, color = TipBlue)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            detail.cast?.takeIf { it.isNotBlank() } ?: "Cast unavailable",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (detail.cast.isNullOrBlank()) TipMuted else TipOnBg
                        )
                        if (error != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = onPlay,
                                enabled = detail.streamUrl.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TipBlue,
                                    contentColor = TipOnAmber,
                                    disabledContainerColor = TipSurfaceAlt,
                                    disabledContentColor = TipMuted
                                )
                            ) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp), tint = TipOnAmber)
                                Spacer(Modifier.width(6.dp))
                                Text("Play", color = TipOnAmber, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = onToggleFavorite,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TipOnBg),
                                border = androidx.compose.foundation.BorderStroke(1.dp, TipBlue)
                            ) {
                                Icon(
                                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                    tint = TipBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (isFavorite) "Unfavorite" else "Favorite",
                                    color = TipOnBg,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesDetailPane(
    detail: SeriesDetail?,
    loading: Boolean,
    error: String?,
    playingTitle: String?,
    isFavorite: Boolean,
    resumeSeason: Int? = null,
    resumeEpisodeNum: Int? = null,
    resumeEpisodeId: String? = null,
    playingThisSeries: Boolean = false,
    playingSeason: Int? = null,
    playingEpisodeNum: Int? = null,
    playingEpisodeId: String? = null,
    playingStreamUrl: String? = null,
    onToggleFavorite: () -> Unit,
    onPlayEpisode: (MediaItem) -> Unit,
    onBack: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TipOnBg)
            }
            Text(
                detail?.name ?: "Series",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (detail != null) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) "Unfavorite" else "Favorite",
                        tint = TipAccent
                    )
                }
            }
            if (playingTitle != null) {
                TextButton(onClick = onStop) { Text("Stop player") }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading && detail == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TipBlue)
                }
            }
            error != null && detail == null -> {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onBack) { Text("Back") }
            }
            detail != null -> {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    RemoteArtwork(
                        url = detail.posterUrl ?: detail.backdropUrl,
                        contentDescription = detail.name,
                        modifier = Modifier.width(100.dp).height(150.dp).clip(RoundedCornerShape(10.dp)),
                        fallbackIcon = Icons.Default.Tv
                    )
                    Column(Modifier.weight(1f)) {
                        val meta = listOfNotNull(
                            detail.year?.toString(),
                            detail.rating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
                            detail.genre?.takeIf { it.isNotBlank() }
                        ).joinToString("  ·  ")
                        if (meta.isNotBlank()) {
                            Text(meta, style = MaterialTheme.typography.bodyMedium, color = TipMuted)
                            Spacer(Modifier.height(6.dp))
                        }
                        if (!detail.plot.isNullOrBlank()) {
                            Text(
                                detail.plot,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            detail.cast?.takeIf { it.isNotBlank() } ?: "Cast unavailable",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (detail.cast.isNullOrBlank()) TipMuted else TipOnBg,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${detail.episodes.size} episodes",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (error != null) {
                            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(10.dp))
                        val continueEp = SeriesPlayback.continueEpisode(
                            detail.episodes, resumeSeason, resumeEpisodeNum, resumeEpisodeId
                        )
                        val nextEp = if (playingThisSeries) {
                            SeriesPlayback.nextAfterPlaying(
                                detail.episodes,
                                playingSeason,
                                playingEpisodeNum,
                                playingEpisodeId,
                                playingStreamUrl
                            )
                        } else {
                            SeriesPlayback.nextActionEpisode(
                                detail.episodes, resumeSeason, resumeEpisodeNum, resumeEpisodeId
                            )
                        }
                        val hasResume = resumeSeason != null || resumeEpisodeNum != null || !resumeEpisodeId.isNullOrBlank()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (continueEp != null) {
                                Button(
                                    onClick = {
                                        onPlayEpisode(continueEp.toMediaItem(detail.name, detail.seriesId))
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TipBlue,
                                        contentColor = TipOnAmber
                                    )
                                ) {
                                    Text(
                                        if (hasResume) {
                                            "Continue S${continueEp.season}E${continueEp.episodeNum}"
                                        } else {
                                            "Play first episode"
                                        },
                                        color = TipOnAmber,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            if (nextEp != null) {
                                OutlinedButton(
                                    onClick = {
                                        onPlayEpisode(nextEp.toMediaItem(detail.name, detail.seriesId))
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TipOnBg),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, TipBlue)
                                ) {
                                    Text("Next S${nextEp.season}E${nextEp.episodeNum}", color = TipOnBg)
                                }
                            }
                        }
                    }
                }
                val seasons = SeriesPlayback.seasonNumbers(detail.episodes)
                var selectedSeason by remember(detail.seriesId, resumeSeason) { mutableStateOf(resumeSeason) }
                if (seasons.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = selectedSeason == null,
                                onClick = { selectedSeason = null },
                                label = { Text("All seasons") }
                            )
                        }
                        items(seasons, key = { "season-chip-$it" }) { season ->
                            FilterChip(
                                selected = selectedSeason == season,
                                onClick = { selectedSeason = season },
                                label = { Text(if (season > 0) "Season $season" else "Specials") }
                            )
                        }
                    }
                }
                val visible = SeriesPlayback.inSeason(detail.episodes, selectedSeason)
                val listState = rememberLazyListState()
                val resumeIndex = remember(visible, resumeSeason, resumeEpisodeNum, resumeEpisodeId) {
                    SeriesPlayback.indexOfEpisode(visible, resumeSeason, resumeEpisodeNum, resumeEpisodeId)
                }
                LaunchedEffect(detail.seriesId, resumeIndex) {
                    if (resumeIndex >= 0) {
                        val header = if (selectedSeason != null) 1 else 0
                        listState.animateScrollToItem(resumeIndex + header)
                    }
                }
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (selectedSeason != null) {
                        item(key = "season-head-$selectedSeason") {
                            Text(
                                if (selectedSeason!! > 0) "Season $selectedSeason" else "Specials",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                    items(visible, key = { it.id }) { ep ->
                        val media = ep.toMediaItem(detail.name, detail.seriesId)
                        val highlighted = SeriesPlayback.matchesEpisodeId(ep, resumeEpisodeId) ||
                            (resumeSeason != null && resumeEpisodeNum != null &&
                                ep.season == resumeSeason && ep.episodeNum == resumeEpisodeNum)
                        MediaRow(
                            media,
                            isFavorite = false,
                            highlighted = highlighted,
                            onClick = { onPlayEpisode(media) },
                            onToggleFavorite = null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavBtn(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) TipBlue.copy(alpha = 0.25f) else TipSurfaceAlt
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (selected) TipBlue else TipMuted)
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) TipBlue else TipOnBg
        )
    }
}

@Composable
private fun MediaRow(
    item: MediaItem,
    isFavorite: Boolean = false,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    onToggleFavorite: (() -> Unit)? = null
) {
    val thumbSize = if (item.kind == ContentKind.LIVE) 44.dp else 56.dp
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlighted) TipBlue.copy(alpha = 0.28f) else TipSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemoteArtwork(
            url = item.artworkUrl(),
            contentDescription = item.name,
            modifier = Modifier
                .width(thumbSize)
                .height(if (item.kind == ContentKind.LIVE) thumbSize else 84.dp)
                .clip(RoundedCornerShape(8.dp)),
            fallbackIcon = when (item.kind) {
                ContentKind.LIVE -> Icons.Default.LiveTv
                ContentKind.VOD -> Icons.Default.Movie
                ContentKind.SERIES -> Icons.Default.Tv
            },
            contentScale = if (item.kind == ContentKind.LIVE) ContentScale.Fit else ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                color = TipOnBg
            )
            val sub = if (highlighted) "Last watched · ${item.groupTitle ?: item.kind.name}" else (item.groupTitle ?: item.kind.name)
            Text(sub, style = MaterialTheme.typography.bodyMedium, maxLines = 1, color = if (highlighted) TipBlue else TipMuted)
        }
        if (onToggleFavorite != null) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorite) "Unfavorite" else "Favorite",
                    tint = TipAccent
                )
            }
        }
        IconButton(onClick = onClick) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = TipAccent)
        }
    }
}
