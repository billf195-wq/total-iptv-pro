package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.FavoriteRef
import com.totaliptv.pro.data.model.MediaItem

/**
 * Live TV Favorites category. Shown at the top of the Live list (Classic, Desktop, phone).
 * Saved favorites already live in app preferences and survive updates; this only
 * exposes the live ones as their own category.
 */
object LiveFavorites {
    const val CATEGORY_ID = "live-favorites"
    const val CATEGORY_NAME = "Favorites"

    fun isFavoritesCategory(categoryId: String?): Boolean = categoryId == CATEGORY_ID

    fun category(): Category = Category(
        id = CATEGORY_ID,
        name = CATEGORY_NAME,
        kind = ContentKind.LIVE
    )

    /** Favorites first, then the provider / built-in live groups unchanged. */
    fun withFavoritesCategory(categories: List<Category>): List<Category> =
        listOf(category()) + categories.filter { it.id != CATEGORY_ID }

    /**
     * Open on Favorites when the viewer already has live favorites; otherwise the
     * first real group, so an empty Favorites list is not the first thing they see.
     */
    fun defaultCategoryId(categories: List<Category>, liveFavoriteCount: Int): String? {
        if (liveFavoriteCount > 0 && categories.any { it.id == CATEGORY_ID }) {
            return CATEGORY_ID
        }
        return categories.firstOrNull { it.id != CATEGORY_ID }?.id
            ?: categories.firstOrNull()?.id
    }

    fun liveFavoriteCount(favorites: List<FavoriteRef>): Int =
        favorites.count { FavoriteSections.kindOf(it) == ContentKind.LIVE }

    /**
     * Favorite live channels in the order they were saved. Prefers the live catalog
     * row (current logo / EPG / playback URL) and falls back to the saved ref.
     */
    fun channels(
        liveItems: List<MediaItem>,
        favorites: List<FavoriteRef>,
        query: String = ""
    ): List<MediaItem> {
        val byId = HashMap<String, MediaItem>(liveItems.size * 2)
        for (item in liveItems) {
            if (item.kind != ContentKind.LIVE) continue
            byId.putIfAbsent(item.id, item)
            byId.putIfAbsent(LiveChannelMapping.liveKey(item), item)
        }
        val out = ArrayList<MediaItem>()
        val seen = HashSet<String>()
        for (fav in favorites) {
            if (FavoriteSections.kindOf(fav) != ContentKind.LIVE) continue
            val item = byId[fav.id] ?: MediaItem(
                id = fav.id,
                name = fav.name,
                streamUrl = fav.streamUrl,
                categoryId = null,
                kind = ContentKind.LIVE,
                logoUrl = fav.logoUrl
            )
            if (!LiveChannelMapping.matchesQuery(item, query)) continue
            val key = LiveChannelMapping.liveKey(item)
            if (!seen.add(key)) continue
            out += item
        }
        return out
    }

    fun filterLive(
        liveItems: List<MediaItem>,
        favorites: List<FavoriteRef>,
        categoryId: String?,
        query: String = ""
    ): List<MediaItem> {
        return if (isFavoritesCategory(categoryId)) {
            channels(liveItems, favorites, query)
        } else {
            LiveChannelMapping.filterLiveChannels(liveItems, categoryId, query)
        }
    }
}
