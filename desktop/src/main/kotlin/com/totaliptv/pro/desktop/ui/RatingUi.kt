package com.totaliptv.pro.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.totaliptv.pro.desktop.artwork.TmdbRatingStore
import com.totaliptv.pro.desktop.data.ContentKind
import com.totaliptv.pro.desktop.data.MediaItem

/** Tile score. Visible cards enqueue themselves so the fetch stays off the first paint. */
@Composable
fun rememberRatingScore(item: MediaItem): Double {
    val revision by TmdbRatingStore.revision.collectAsState()
    LaunchedEffect(item.id, item.tmdbId, item.kind) {
        TmdbRatingStore.enqueue(listOf(item), front = true)
    }
    return remember(revision, item.id, item.rating, item.rating5Based, item.tmdbId, item.kind) {
        TmdbRatingStore.displayScore(item)
    }
}

/** Detail score. Asks for this title first, then shows the cached TMDB average when it arrives. */
@Composable
fun rememberDetailRating(kind: ContentKind, tmdbId: String?, providerRating: String?): Double {
    val revision by TmdbRatingStore.revision.collectAsState()
    LaunchedEffect(kind, tmdbId) {
        val id = tmdbId?.trim()?.takeIf { it.isNotEmpty() && it != "0" } ?: return@LaunchedEffect
        TmdbRatingStore.enqueue(
            listOf(
                MediaItem(
                    id = "tmdb-$kind-$id",
                    name = "",
                    streamUrl = "",
                    categoryId = null,
                    kind = kind,
                    tmdbId = id,
                    rating = providerRating
                )
            ),
            front = true
        )
    }
    return remember(revision, kind, tmdbId, providerRating) {
        TmdbRatingStore.displayScore(kind, tmdbId, providerRating)
    }
}

fun formatRating(score: Double): String? =
    if (score > 0.0) "★ " + String.format("%.1f", score) else null
