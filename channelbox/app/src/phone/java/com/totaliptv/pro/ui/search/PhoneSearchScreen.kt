package com.totaliptv.pro.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.ui.components.PhoneDetailSheet
import com.totaliptv.pro.ui.components.PhoneLiveRow
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import com.totaliptv.pro.ui.theme.tipScreenBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PhoneSearchScreen(
    repository: CatalogRepository,
    onPlay: (MediaItem) -> Unit,
    onPlayFromStart: (MediaItem) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val revision by repository.catalogRevision.collectAsState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var detail by remember { mutableStateOf<MediaItem?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { repository.ensureCatalogLoaded() } }
    }

    LaunchedEffect(query, revision) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(180)
        results = withContext(Dispatchers.Default) { repository.searchCatalog(q, limit = 80) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tipScreenBrush())
            .padding(contentPadding)
    ) {
        Text(
            text = "Search",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = OnCinema,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Channels, movies, series…") }
        )
        when {
            query.trim().length < 2 -> {
                Text(
                    "Type at least 2 characters",
                    color = OnCinemaMuted,
                    modifier = Modifier.padding(16.dp)
                )
            }
            results.isEmpty() -> {
                Text(
                    "No matches",
                    color = OnCinemaMuted,
                    modifier = Modifier.padding(16.dp)
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(results, key = { it.id }) { item ->
                        PhoneLiveRow(
                            item = item,
                            onClick = {
                                if (item.kind == ContentKind.LIVE) onPlay(item) else detail = item
                            }
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
