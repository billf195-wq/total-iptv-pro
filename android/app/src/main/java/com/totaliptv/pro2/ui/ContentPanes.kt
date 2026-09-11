package com.totaliptv.pro2.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.totaliptv.pro2.data.Catalog
import com.totaliptv.pro2.data.Category
import com.totaliptv.pro2.data.ChannelEpg
import com.totaliptv.pro2.data.MediaItem
import com.totaliptv.pro2.update.UpdatePhase
import com.totaliptv.pro2.update.UpdateUiState
import com.totaliptv.pro2.data.ResumeStore
import com.totaliptv.pro2.data.SavedPrefs
import com.totaliptv.pro2.data.SeriesDetail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomePane(
    catalog: Catalog,
    resume: List<ResumeStore.ResumeEntry>,
    onPlay: (MediaItem) -> Unit,
    onOpenSeries: (MediaItem) -> Unit,
    onResume: (ResumeStore.ResumeEntry) -> Unit
) {
    val top = remember(catalog) { pickTopRatedMovies(catalog) }
    val topSeries = remember(catalog) { pickTopRatedSeries(catalog) }
    LazyColumn(
        Modifier.fillMaxSize(),
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
                LazyRow(horizontalArrangement = Arrangement.spacedBy(TipDimens.PosterRowGap)) {
                    items(resume.take(24), key = { it.key }) { entry ->
                        val proxy = MediaItem(
                            id = entry.catalogId,
                            name = entry.name,
                            streamUrl = entry.streamUrl,
                            categoryId = null,
                            kind = com.totaliptv.pro2.data.ContentKind.valueOf(entry.kind),
                            posterUrl = entry.posterUrl,
                            playable = true
                        )
                        PosterCard(proxy, onClick = { onResume(entry) })
                    }
                }
            }
        }
        item {
            SectionHeader(top.title)
            if (top.items.isEmpty()) {
                Text("No movies in catalog yet.", color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(TipDimens.PosterRowGap)) {
                    items(top.items, key = { it.id }) { item ->
                        PosterCard(item, onClick = { onPlay(item) })
                    }
                }
            }
        }
        item {
            SectionHeader(topSeries.title)
            if (topSeries.items.isEmpty()) {
                Text("No series in catalog yet.", color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(TipDimens.PosterRowGap)) {
                    items(topSeries.items, key = { it.id }) { item ->
                        PosterCard(item, onClick = { onOpenSeries(item) })
                    }
                }
            }
        }
        if (catalog.warnings.isNotEmpty()) {
            item {
                catalog.warnings.forEach {
                    Text(it, color = TipAccent, fontSize = TipDimens.sp(12))
                }
            }
        }
        item { Spacer(Modifier.height(TipDimens.dp(24))) }
    }
}

@Composable
fun LivePane(
    catalog: Catalog,
    search: String,
    categoryId: String?,
    onSearch: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onPlay: (MediaItem) -> Unit
) {
    val filtered = remember(catalog, search, categoryId) {
        catalog.liveItems
            .filter { categoryId == null || it.categoryId == categoryId }
            .filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        FilterBar(
            search = search,
            onSearch = onSearch,
            categories = catalog.liveCategories,
            categoryId = categoryId,
            onCategory = onCategory,
            sort = null,
            onSort = null
        )
        Text("${filtered.size} channels", color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp)
        Spacer(Modifier.height(TipDimens.dp(8)))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(TipDimens.dp(6))) {
            items(filtered, key = { it.id }) { item ->
                LiveRowItem(item, onClick = { onPlay(item) })
            }
        }
    }
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
    onSearch: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onSort: (String) -> Unit,
    onClick: (MediaItem) -> Unit
) {
    val filtered = remember(items, search, categoryId, sort) {
        var list = items
            .filter { categoryId == null || it.categoryId == categoryId }
            .filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
        list = when (sort) {
            "ZA" -> list.sortedByDescending { it.name.lowercase() }
            "RECENT" -> list.sortedByDescending { it.addedEpoch }
            else -> list.sortedBy { it.name.lowercase() }
        }
        list
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
            columns = GridCells.Fixed(columns.coerceIn(5, 6)),
            horizontalArrangement = Arrangement.spacedBy(TipDimens.PosterRowGap),
            verticalArrangement = Arrangement.spacedBy(TipDimens.PosterRowGap),
            contentPadding = PaddingValues(bottom = TipDimens.dp(24)),
            modifier = Modifier.fillMaxSize()
        ) {
            gridItems(filtered, key = { it.id }) { item ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    PosterCard(item, onClick = { onClick(item) })
                }
            }
        }
    }
}

@Composable
fun SeriesDetailPane(
    detail: SeriesDetail?,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onPlay: (MediaItem) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        AmberButton("Back", onClick = onBack)
        Spacer(Modifier.height(TipDimens.dp(12)))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TipAmber)
            }
            error != null -> Text(error, color = androidx.compose.ui.graphics.Color(0xFFFF8A80))
            detail != null -> {
                Text(detail.name, color = TipGoldText, fontSize = TipDimens.HeadlineMediumSp, fontWeight = FontWeight.SemiBold)
                detail.plot?.let {
                    Text(it, color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(TipDimens.dp(12)))
                LazyColumn {
                    items(detail.episodes, key = { it.id }) { ep ->
                        TipFocusable(
                            onClick = { onPlay(ep.toMediaItem(detail.name, detail.seriesId)) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = TipDimens.dp(2))
                        ) { focused ->
                            Text(
                                "S${ep.season}E${ep.episodeNum} — ${ep.title}",
                                color = if (focused) TipAccent else TipGoldText,
                                fontSize = TipDimens.BodyLargeSp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (focused) TipSurfaceAlt else TipSurface)
                                    .padding(TipDimens.dp(12))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GuidePane(
    catalog: Catalog,
    epgByStreamId: Map<Int, ChannelEpg>,
    epgLoadingIds: Set<Int>,
    categoryId: String?,
    onCategory: (String?) -> Unit,
    onNeedEpg: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit
) {
    val channels = remember(catalog, categoryId) {
        catalog.liveItems.filter { categoryId == null || it.categoryId == categoryId }.take(200)
    }
    var selected by remember { mutableStateOf<MediaItem?>(null) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LaunchedEffect(selected) {
        selected?.let { onNeedEpg(it) }
    }

    Column(Modifier.fillMaxSize()) {
        PaneTitle("TV Guide")
        FilterBar(
            search = "",
            onSearch = {},
            categories = catalog.liveCategories,
            categoryId = categoryId,
            onCategory = onCategory,
            sort = null,
            onSort = null,
            showSearch = false
        )
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(0.4f)) {
                items(channels, key = { it.id }) { ch ->
                    TipFocusable(
                        onClick = {
                            selected = ch
                            onNeedEpg(ch)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { focused ->
                        val sel = selected?.id == ch.id
                        Text(
                            ch.name,
                            color = when {
                                sel -> TipOnAmber
                                focused -> TipAccent
                                else -> TipGoldText
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        sel -> TipAmber
                                        focused -> TipSurfaceAlt
                                        else -> TipSurface
                                    }
                                )
                                .padding(TipDimens.dp(10))
                        )
                    }
                }
            }
            Spacer(Modifier.width(TipDimens.dp(12)))
            Column(Modifier.weight(0.6f)) {
                val ch = selected
                if (ch == null) {
                    Text("Select a channel", color = TipGoldMuted)
                } else {
                    Text(ch.name, color = TipAmber, fontWeight = FontWeight.Bold, fontSize = TipDimens.TitleLargeSp)
                    AmberButton("Watch", onClick = { onPlay(ch) })
                    Spacer(Modifier.height(TipDimens.dp(8)))
                    val sid = ch.xtreamStreamId
                    when {
                        sid != null && sid in epgLoadingIds -> CircularProgressIndicator(color = TipAmber)
                        sid != null -> {
                            val programs = epgByStreamId[sid]?.programs.orEmpty()
                            if (programs.isEmpty()) {
                                Text("No EPG for this channel", color = TipGoldMuted)
                            } else {
                                LazyColumn {
                                    items(programs, key = { it.id }) { prog ->
                                        val now = prog.contains()
                                        TipFocusable(
                                            onClick = { onPlay(ch) },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = TipDimens.dp(2))
                                        ) { focused ->
                                            Column(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .background(if (now) TipSurfaceAlt else TipSurface)
                                                    .padding(TipDimens.dp(10))
                                            ) {
                                                Text(
                                                    "${timeFmt.format(Date(prog.startMs))}–${timeFmt.format(Date(prog.endMs))}" +
                                                        if (now) "  • NOW" else "",
                                                    color = if (now || focused) TipAccent else TipGoldMuted,
                                                    fontSize = TipDimens.BodyMediumSp
                                                )
                                                Text(
                                                    prog.title,
                                                    color = if (focused) TipAccent else TipGoldText,
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = TipDimens.BodyLargeSp
                                                )
                                                prog.description?.let {
                                                    Text(it, color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp, maxLines = 2)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsPane(
    prefs: SavedPrefs,
    catalog: Catalog,
    update: UpdateUiState,
    onPosterColumns: (Int) -> Unit,
    onRefresh: () -> Unit,
    onChangeSource: () -> Unit,
    onCheckAppUpdate: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onInstallAppUpdate: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        PaneTitle("Settings")
        Text("Source: Xtream", color = TipGoldText, fontSize = TipDimens.BodyLargeSp)
        Text(prefs.xtreamBaseUrl, color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp)
        Text("User: ${prefs.xtreamUsername}", color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp)
        Spacer(Modifier.height(TipDimens.dp(12)))
        Text(
            "Live ${catalog.liveItems.size} · Movies ${catalog.vodItems.size} · Series ${catalog.seriesItems.size}",
            color = TipGoldText
        )
        Spacer(Modifier.height(TipDimens.dp(16)))
        Text("Poster columns", color = TipGoldMuted)
        Row {
            listOf(5, 6).forEach { n ->
                TipFocusable(
                    onClick = { onPosterColumns(n) },
                    modifier = Modifier.padding(end = TipDimens.dp(8))
                ) { focused ->
                    val sel = prefs.posterColumns == n
                    Text(
                        "$n",
                        color = if (sel) TipOnAmber else if (focused) TipAccent else TipGoldText,
                        modifier = Modifier
                            .background(if (sel) TipAmber else TipSurface, RoundedCornerShape(TipDimens.dp(6)))
                            .padding(horizontal = TipDimens.dp(16), vertical = TipDimens.dp(10))
                    )
                }
            }
        }
        Spacer(Modifier.height(TipDimens.dp(20)))
        AmberButton("Update (reload catalog)", onClick = onRefresh)
        Spacer(Modifier.height(TipDimens.dp(10)))
        AmberButton("Change source", onClick = onChangeSource)

        Spacer(Modifier.height(TipDimens.dp(24)))
        Text("App updates", color = TipGoldText, fontSize = TipDimens.BodyLargeSp, fontWeight = FontWeight.SemiBold)
        Text(
            "Installed: ${update.localVersionName.ifBlank { "?" }} (${update.localVersionCode})",
            color = TipGoldMuted,
            fontSize = TipDimens.BodyMediumSp
        )
        Spacer(Modifier.height(TipDimens.dp(10)))
        AmberButton(
            label = when (update.phase) {
                UpdatePhase.Checking -> "Checking…"
                UpdatePhase.Downloading -> "Downloading…"
                else -> "Check for update"
            },
            onClick = onCheckAppUpdate
        )
        if (update.phase == UpdatePhase.Available) {
            Spacer(Modifier.height(TipDimens.dp(10)))
            AmberButton("Download update", onClick = onDownloadAppUpdate)
        }
        if (update.phase == UpdatePhase.ReadyToInstall && update.apkFile != null) {
            Spacer(Modifier.height(TipDimens.dp(10)))
            AmberButton("Install update", onClick = onInstallAppUpdate)
        }
        if (update.message.isNotBlank()) {
            Spacer(Modifier.height(TipDimens.dp(10)))
            val color = when (update.phase) {
                UpdatePhase.Error -> androidx.compose.ui.graphics.Color(0xFFFF8A80)
                UpdatePhase.UpToDate -> TipAccent
                UpdatePhase.Available, UpdatePhase.ReadyToInstall -> TipAmber
                else -> TipGoldMuted
            }
            Text(update.message, color = color, fontSize = TipDimens.BodyMediumSp)
            update.shelfBaseUrl?.let { base ->
                Text("Shelf: $base", color = TipGoldMuted, fontSize = TipDimens.sp(12))
            }
        }

        Spacer(Modifier.height(TipDimens.dp(24)))
        Text("Total IPTV Pro 2 — installable beside Total IPTV Pro", color = TipGoldMuted, fontSize = TipDimens.sp(12))
        Text("Package: com.totaliptv.pro2", color = TipGoldMuted, fontSize = TipDimens.sp(12))
    }
}

@Composable
private fun FilterBar(
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
