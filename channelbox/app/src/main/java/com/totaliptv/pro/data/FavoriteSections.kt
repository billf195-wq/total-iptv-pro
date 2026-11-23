package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.FavoriteRef
import com.totaliptv.pro.data.model.MediaItem

/**
 * Splits the saved favorites list into Live / Movies / Series, keeping each group in saved order.
 *
 * Classic shows each group as a "Favorites" row at the top of that section's category sidebar
 * (Live already had one). The saved list itself is never rewritten: a favorite is sorted by the
 * kind its id or stream URL proves ([kindOf]), so older mixed lists land in the right section
 * and nothing is dropped.
 */
object FavoriteSections {
    const val MOVIES_CATEGORY_ID = "favorites-movies"
    const val SERIES_CATEGORY_ID = "favorites-series"
    const val CATEGORY_NAME = "Favorites"

    /**
     * The section a saved favorite belongs to. Xtream ids carry their kind ("live-", "vod-",
     * "series-", "series-ep-"); other playlists fall back to the stream path, then to the
     * kind saved with the favorite.
     */
    fun kindOf(fav: FavoriteRef): ContentKind {
        val id = fav.id
        when {
            id.startsWith("series-") -> return ContentKind.SERIES
            id.startsWith("vod-") -> return ContentKind.VOD
            id.startsWith("live-") -> return ContentKind.LIVE
        }
        val path = urlPath(fav.streamUrl)
        return when {
            path.contains("/series/") -> ContentKind.SERIES
            path.contains("/movie/") -> ContentKind.VOD
            else -> fav.kind
        }
    }

    fun of(favorites: List<FavoriteRef>, kind: ContentKind): List<FavoriteRef> =
        favorites.filter { kindOf(it) == kind }

    fun live(favorites: List<FavoriteRef>): List<FavoriteRef> = of(favorites, ContentKind.LIVE)

    fun movies(favorites: List<FavoriteRef>): List<FavoriteRef> = of(favorites, ContentKind.VOD)

    fun series(favorites: List<FavoriteRef>): List<FavoriteRef> = of(favorites, ContentKind.SERIES)

    fun count(favorites: List<FavoriteRef>, kind: ContentKind): Int =
        favorites.count { kindOf(it) == kind }

    fun categoryId(kind: ContentKind): String = when (kind) {
        ContentKind.LIVE -> LiveFavorites.CATEGORY_ID
        ContentKind.VOD -> MOVIES_CATEGORY_ID
        ContentKind.SERIES -> SERIES_CATEGORY_ID
    }

    fun isFavoritesCategory(categoryId: String?): Boolean =
        categoryId == LiveFavorites.CATEGORY_ID ||
            categoryId == MOVIES_CATEGORY_ID ||
            categoryId == SERIES_CATEGORY_ID

    fun category(kind: ContentKind): Category =
        if (kind == ContentKind.LIVE) LiveFavorites.category()
        else Category(id = categoryId(kind), name = CATEGORY_NAME, kind = kind)

    /** Favorites first, then the provider groups unchanged. */
    fun withFavoritesCategory(kind: ContentKind, categories: List<Category>): List<Category> {
        val id = categoryId(kind)
        return listOf(category(kind)) + categories.filter { it.id != id }
    }

    /** True when the only row is Favorites (provider groups not loaded / none). */
    fun onlyFavorites(categories: List<Category>): Boolean =
        categories.all { isFavoritesCategory(it.id) }

    /**
     * Open on Favorites when that section already has favorites (same as Live); otherwise the
     * first real group, so an empty Favorites list is not the first thing they see.
     */
    fun defaultCategoryId(kind: ContentKind, categories: List<Category>, favorites: List<FavoriteRef>): String? {
        val id = categoryId(kind)
        if (count(favorites, kind) > 0 && categories.any { it.id == id }) return id
        return categories.firstOrNull { it.id != id }?.id ?: categories.firstOrNull()?.id
    }

    /**
     * Favorite movies or series in saved order. Prefers the catalog row (current poster, rating,
     * playback URL) and falls back to the saved ref, so a title missing from the current catalog
     * still shows and can be removed.
     */
    fun items(kind: ContentKind, favorites: List<FavoriteRef>, catalog: List<MediaItem>): List<MediaItem> {
        val mine = of(favorites, kind)
        if (mine.isEmpty()) return emptyList()
        val wanted = HashSet<String>(mine.size * 2)
        mine.forEach { wanted += it.id }
        val byId = HashMap<String, MediaItem>(mine.size * 2)
        for (item in catalog) {
            if (item.id in wanted) byId.putIfAbsent(item.id, item)
        }
        val seen = HashSet<String>()
        val out = ArrayList<MediaItem>(mine.size)
        for (fav in mine) {
            if (!seen.add(fav.id)) continue
            out += byId[fav.id] ?: MediaItem(
                id = fav.id,
                name = fav.name,
                streamUrl = fav.streamUrl,
                categoryId = null,
                kind = kind,
                logoUrl = fav.logoUrl,
                posterUrl = fav.logoUrl
            )
        }
        return out
    }

    /** A saved single episode plays directly; there is no series page for it. */
    fun isEpisode(id: String): Boolean = id.startsWith("series-ep-")

    private fun urlPath(url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return ""
        return try {
            java.net.URI(raw).rawPath?.lowercase().orEmpty()
        } catch (_: Exception) {
            raw.substringBefore('?').lowercase()
        }
    }
}
