package com.totaliptv.pro.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.model.MediaItem

/**
 * Badge text using the same merged rating as Top rated: TMDB when a key is set,
 * otherwise the provider rating. Recomputes when the catalog / TMDB index revises.
 */
@Composable
fun mergedDisplayRating(item: MediaItem): String? {
    val repo = (LocalContext.current.applicationContext as? TotalIptvProApp)?.repository
        ?: return item.displayRating()
    val revision by repo.catalogRevision.collectAsState()
    return remember(item.id, item.rating, revision) { repo.displayRatingOf(item) }
}
