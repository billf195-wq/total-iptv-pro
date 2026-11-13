package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState

/** Visible item index and pixel offset within that item. */
data class ScrollSpot(val index: Int = 0, val offset: Int = 0) {
    val isOrigin: Boolean get() = index <= 0 && offset <= 0
}

/**
 * Scroll positions that outlive a title-page (the list is removed from
 * composition while a movie/series detail is showing). Keys are per screen,
 * category, sort, and per horizontal row.
 */
class BrowseScrollStates(initial: Map<String, ScrollSpot> = emptyMap()) {
    private val initials = initial.toMutableMap()
    private val lists = mutableMapOf<String, LazyListState>()
    private val grids = mutableMapOf<String, LazyGridState>()

    fun list(key: String): LazyListState = lists.getOrPut(key) {
        val spot = initials[key] ?: ScrollSpot()
        LazyListState(spot.index.coerceAtLeast(0), spot.offset.coerceAtLeast(0))
    }

    fun grid(key: String): LazyGridState = grids.getOrPut(key) {
        val spot = initials[key] ?: ScrollSpot()
        LazyGridState(spot.index.coerceAtLeast(0), spot.offset.coerceAtLeast(0))
    }

    fun snapshot(key: String): ScrollSpot {
        lists[key]?.let {
            return ScrollSpot(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset)
        }
        grids[key]?.let {
            return ScrollSpot(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset)
        }
        return initials[key] ?: ScrollSpot()
    }

    fun snapshotsPrefixed(prefix: String): Map<String, ScrollSpot> {
        val keys = (lists.keys + grids.keys + initials.keys).filter { it == prefix || it.startsWith("$prefix:") }
        return keys.associateWith { snapshot(it) }
    }

    companion object {
        const val HOME = "home"
        const val HOME_CONTINUE = "home:continue"
        const val HOME_MOVIES = "home:movies"
        const val HOME_SERIES = "home:series"
        const val FAVORITES = "favorites"
        fun live(categoryId: String?) = "live:${categoryId ?: "all"}"
        fun movies(categoryId: String?, sort: String) = "movies:${categoryId ?: "all"}:$sort"
        fun series(categoryId: String?, sort: String) = "series:${categoryId ?: "all"}:$sort"
        fun chips(kind: String) = "chips:$kind"
    }
}
