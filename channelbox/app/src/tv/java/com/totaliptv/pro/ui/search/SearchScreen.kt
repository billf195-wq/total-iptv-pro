package com.totaliptv.pro.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import com.totaliptv.pro.ui.components.DpadSearchField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.totaliptv.pro.data.model.ContentKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import com.totaliptv.pro.ui.components.MovieDetailSheet
import com.totaliptv.pro.data.local.WatchProgressStore
import com.totaliptv.pro.TotalIptvProApp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import android.widget.Toast
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.ui.components.ChannelListItem
import com.totaliptv.pro.ui.components.TopBarChip
import com.totaliptv.pro.ui.theme.BrandBlue
import com.totaliptv.pro.ui.theme.tipScreenBrush
import com.totaliptv.pro.ui.theme.CinemaSurface
import com.totaliptv.pro.ui.theme.FocusBorder
import com.totaliptv.pro.ui.theme.Hairline
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    repository: CatalogRepository,
    onPlay: (MediaItem) -> Unit,
    onBack: () -> Unit,
    onPlayFromStart: (MediaItem) -> Unit = onPlay
) {
    val catalogRevision by repository.catalogRevision.collectAsState()
    val vodLoading by repository.vodLoading.collectAsState()
    var query by remember { mutableStateOf("") }
    // Latest filter hits (updated after debounce). Not shown until typing freeze lifts.
    var committedResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    // What LazyColumn actually renders — frozen while IME/typing so list swaps cannot jump the query row.
    var displayedResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    // Back leaves Search. While the field is editing, DpadSearchField handles Back first.
    BackHandler(enabled = !fieldFocused) { onBack() }
    // Bumps on every keystroke; gates when displayedResults may update.
    var typingEpoch by remember { mutableStateOf(0) }
    val closeFocus = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detailItem by remember { mutableStateOf<MediaItem?>(null) }
    var favorites by remember { mutableStateOf<List<com.totaliptv.pro.data.model.FavoriteRef>>(emptyList()) }
    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }

    LaunchedEffect(Unit) {
        repository.favorites.collectLatest { favorites = it }
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
    // Results must NOT steal focus while typing. Only enable after explicit Down/Enter/IME Search.
    var resultsNavigable by remember { mutableStateOf(false) }
    var pendingMoveToResults by remember { mutableStateOf(false) }

    // Land on Close, not the search field, so the keyboard stays closed on entry.
    LaunchedEffect(Unit) {
        delay(24)
        runCatching { closeFocus.requestFocus() }
    }

    // Debounced catalog search (550ms). Does not touch TextField focus/selection.
    // Holds previous displayedResults until a settled commit is published (no mid-type list teardown).
    LaunchedEffect(query, catalogRevision) {
        val q = query.trim()
        if (q.length < 2) {
            searching = false
            committedResults = emptyList()
            delay(120)
            displayedResults = emptyList()
            resultsNavigable = false
            pendingMoveToResults = false
            return@LaunchedEffect
        }
        delay(550)
        searching = true
        try {
            val found = withContext(Dispatchers.Default) {
                repository.searchCatalog(q, limit = 80)
            }
            committedResults = found
        } finally {
            searching = false
        }
    }

    // Publish committed -> displayed only after typing freeze (~500ms since last keystroke)
    // OR when the field is no longer focused (user left for results / Close).
    // While fieldFocused + recently typing, keep showing the previous list (static) to avoid layout jump.
    LaunchedEffect(committedResults, searching, fieldFocused, typingEpoch) {
        if (searching) return@LaunchedEffect
        if (fieldFocused) {
            val epochAtStart = typingEpoch
            delay(500)
            // Another keystroke arrived during freeze — cancel; newer effect will run.
            if (typingEpoch != epochAtStart) return@LaunchedEffect
        }
        displayedResults = committedResults
    }

    // Explicit navigation only: after Down/Enter enables resultsNavigable, move focus once.
    LaunchedEffect(pendingMoveToResults, resultsNavigable, displayedResults) {
        if (!pendingMoveToResults || !resultsNavigable || displayedResults.isEmpty()) return@LaunchedEffect
        delay(32) // let canFocus=true apply before requestFocus
        if (runCatching { firstResultFocus.requestFocus() }.isSuccess) {
            pendingMoveToResults = false
        }
    }

    fun goToResults(): Boolean {
        // Flush latest committed hits before leaving the field.
        if (committedResults.isNotEmpty()) {
            displayedResults = committedResults
        }
        if (displayedResults.isEmpty()) return false
        resultsNavigable = true
        pendingMoveToResults = true
        return true
    }

    val liveCount = displayedResults.count { it.kind == ContentKind.LIVE }
    val vodCount = displayedResults.size - liveCount
    val trimmed = query.trim()
    val showVodBanner = vodLoading && trimmed.length >= 2

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tipScreenBrush())
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        // Stable top chrome — always composed, never keyed by query/results.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TopBarChip(
                label = "Close",
                onClick = onBack,
                emphasized = true,
                // While typing, Close must not compete for focus / steal IME.
                modifier = Modifier
                    .focusRequester(closeFocus)
                    .focusProperties { canFocus = !fieldFocused }
            )
            Text(
                text = "Search",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = OnCinema
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = when {
                    trimmed.isEmpty() -> "Live + movies + series"
                    trimmed.length < 2 -> "Type at least 2 characters"
                    searching && displayedResults.isEmpty() -> "Searching\u2026"
                    searching -> "Updating\u2026"
                    displayedResults.isEmpty() -> "0 results"
                    else -> "$liveCount live \u00b7 $vodCount movies"
                },
                style = MaterialTheme.typography.labelLarge,
                color = BrandBlue.copy(alpha = 0.9f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // Pinned query row — fixed height, never leaves composition, never keyed by query.
        DpadSearchField(
            value = query,
            onValueChange = {
                query = it
                typingEpoch += 1
                // Typing again locks results so LazyColumn cannot steal focus mid-IME.
                resultsNavigable = false
                pendingMoveToResults = false
            },
            placeholder = "Search channels, movies, series\u2026",
            textStyle = TextStyle(
                color = OnCinema,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            ),
            placeholderColor = OnCinemaMuted,
            cursorColor = BrandBlue,
            backgroundColor = CinemaSurface,
            focusedBorderColor = FocusBorder,
            idleBorderColor = Hairline,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            downFocus = if (displayedResults.isNotEmpty()) firstResultFocus else null,
            onEditingChange = { fieldFocused = it },
            onExitEdit = { toNext ->
                if (toNext) goToResults() else false
            },
            modifier = Modifier.height(48.dp)
        )

        // Fixed-height status slot so vodLoading banner never shifts the query row.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(top = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (showVodBanner) {
                Text(
                    text = "Movies still loading\u2026 live results shown now; movies appear when ready.",
                    style = MaterialTheme.typography.labelLarge,
                    color = OnCinemaMuted
                )
            }
        }

        // Results pane — weight(1f) so query row stays pinned; content swaps inside without relayouting the field.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                trimmed.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Start typing \u2014 searches live channels, movies, and series.\nUse the emulator keyboard or Android TV soft keyboard.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnCinemaMuted
                        )
                    }
                }
                trimmed.length < 2 -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Type at least 2 characters to search.",
                            style = MaterialTheme.typography.titleMedium,
                            color = OnCinemaMuted
                        )
                    }
                }
                displayedResults.isEmpty() && searching -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Searching\u2026",
                            style = MaterialTheme.typography.titleMedium,
                            color = OnCinemaMuted
                        )
                    }
                }
                displayedResults.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (vodLoading) {
                                "No live matches yet for \"$query\".\nMovies still loading\u2026"
                            } else {
                                "No matches for \"$query\""
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = OnCinemaMuted
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 28.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            // Block accidental focus entry until user presses Down.
                            .focusProperties { canFocus = resultsNavigable }
                    ) {
                        itemsIndexed(displayedResults, key = { _, item -> item.id }) { index, item ->
                            val kindLabel = repository.searchKindLabel(item)
                            val detail = item.groupTitle?.takeIf { it.isNotBlank() }
                            ChannelListItem(
                                title = item.name,
                                logoUrl = item.artworkUrl(),
                                subtitle = if (detail != null && (item.kind == ContentKind.VOD || item.kind == ContentKind.SERIES)) {
                                    "$kindLabel \u00b7 $detail"
                                } else {
                                    kindLabel
                                },
                                onClick = {
                                    when (item.kind) {
                                        ContentKind.VOD, ContentKind.SERIES -> detailItem = item
                                        else -> {
                                            if (item.streamUrl.isNotBlank()) onPlay(item)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .then(
                                        if (index == 0) Modifier.focusRequester(firstResultFocus)
                                        else Modifier
                                    )
                                    .focusProperties { canFocus = resultsNavigable }
                            )
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
                detailItem = null
                onPlay(detail)
            },
            onPlayItem = { playTarget ->
                detailItem = null
                onPlay(playTarget)
            },
            onResume = {
                val playTarget = detailProgress?.toMediaItem() ?: detail
                detailItem = null
                onPlay(playTarget)
            },
            onPlayFromStart = {
                runCatching {
                    detailProgress?.id?.let { store.clear(it) }
                    store.clear(detail.id)
                    detailProgress?.catalogId?.let { store.clear(it) }
                }
                detailItem = null
                onPlayFromStart(detail)
            },
            onToggleFavorite = { toggleFavoriteToast(detail) },
            onDismiss = { detailItem = null }
        )
    }
}
