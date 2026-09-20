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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.data.LiveChannelMapping
import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.data.repo.CatalogSort
import com.totaliptv.pro.ui.components.PhoneDetailSheet
import com.totaliptv.pro.ui.components.PhoneLiveRow
import com.totaliptv.pro.ui.components.PhonePosterCard
import com.totaliptv.pro.ui.theme.BrandBlue
import com.totaliptv.pro.ui.theme.CinemaSurface
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import com.totaliptv.pro.ui.theme.tipScreenBrush
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
    var loading by remember { mutableStateOf(true) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var selectedCat by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(CatalogSort.RECENTLY_ADDED) }
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var detail by remember { mutableStateOf<MediaItem?>(null) }

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
            categories = repository.categories(kind)
            if (selectedCat == null || categories.none { it.id == selectedCat }) {
                selectedCat = categories.firstOrNull()?.id
            }
        }
        loading = false
    }

    LaunchedEffect(section, selectedCat, sort, revision) {
        items = when (section) {
            BrowseSection.Favorites -> emptyList() // filled below via flow
            BrowseSection.Live -> {
                val aligned = LiveChannelMapping.filterLiveChannels(
                    repository.liveItems(),
                    selectedCat
                )
                if (sort == CatalogSort.RECENTLY_ADDED) aligned
                else repository.sortCatalogItems(aligned, sort)
            }
            else -> {
                val raw = selectedCat?.let { repository.itemsForCategory(it) }.orEmpty()
                repository.sortCatalogItems(raw, sort)
            }
        }
    }

    val favorites by repository.favorites.collectAsState(initial = emptyList())
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
        Text(
            text = when (section) {
                BrowseSection.Live -> "Live"
                BrowseSection.Movies -> "Movies"
                BrowseSection.Series -> "Series"
                BrowseSection.Favorites -> "Favorites"
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = OnCinema,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

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
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    CatalogSort.RECENTLY_ADDED to "Newest",
                    CatalogSort.AZ to "A–Z",
                    CatalogSort.ZA to "Z–A"
                ).forEach { (mode, label) ->
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
            displayItems.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing here yet", color = OnCinemaMuted)
                }
            }
            section == BrowseSection.Live || section == BrowseSection.Favorites -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(displayItems, key = { it.id }) { item ->
                        PhoneLiveRow(
                            item = item,
                            onClick = {
                                if (item.kind == ContentKind.LIVE) onPlay(item) else detail = item
                            }
                        )
                    }
                }
            }
            else -> {
                LazyVerticalGrid(
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
