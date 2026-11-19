package com.totaliptv.pro.desktop.data

import com.totaliptv.pro.desktop.data.tmdb.ServiceIndex
import com.totaliptv.pro.desktop.ui.HomeDedupe

/** Movies / Series virtual groups and item lists, shared by the UI and unit tests. */
object BrowseCatalog {
    const val NEWLY_ADDED_LIMIT = 150

    fun itemsOf(kind: ContentKind, catalog: Catalog): List<MediaItem> = when (kind) {
        ContentKind.LIVE -> catalog.liveItems
        ContentKind.VOD -> catalog.vodItems
        ContentKind.SERIES -> catalog.seriesItems
    }

    fun categories(kind: ContentKind, catalog: Catalog, index: ServiceIndex?): List<Category> {
        val provider = when (kind) {
            ContentKind.LIVE -> catalog.liveCategories
            ContentKind.VOD -> catalog.vodCategories
            ContentKind.SERIES -> catalog.seriesCategories
        }
        if (kind == ContentKind.LIVE) return provider
        val items = HomeDedupe.dropIdenticalIds(itemsOf(kind, catalog))
        if (items.isEmpty()) return provider.filter { !CatalogCategories.isVirtual(it.id) }
        val grouped = StreamingServices.group(items, extra = { index?.lookup(it) ?: emptySet() })
        val serviceCats = StreamingServices.categoriesFor(kind, grouped)
        val hasRated = index != null && items.any { index.ratingScore(it) != null }
        return CatalogCategories.build(
            kind = kind,
            provider = provider,
            hasItems = true,
            hasRated = hasRated,
            serviceCategories = serviceCats
        )
    }

    fun defaultCategoryId(kind: ContentKind, categories: List<Category>): String? =
        categories.firstOrNull { CatalogCategories.isNewlyAddedId(it.id) }?.id
            ?: categories.firstOrNull()?.id

    fun belongsTo(kind: ContentKind, categoryId: String?, categories: List<Category>): Boolean {
        if (categoryId == null) return true // "All"
        return categories.any { it.id == categoryId } ||
            (kind == ContentKind.LIVE && categoryId.startsWith("live-"))
    }

    fun items(
        kind: ContentKind,
        catalog: Catalog,
        categoryId: String?,
        index: ServiceIndex?
    ): List<MediaItem> {
        val all = HomeDedupe.dropIdenticalIds(itemsOf(kind, catalog))
        if (kind == ContentKind.LIVE) return all
        return when {
            categoryId == null -> all
            CatalogCategories.isNewlyAddedId(categoryId) -> newlyAdded(all)
            CatalogCategories.isTopRatedId(categoryId) ->
                Ratings.topRatedOnly(all, CatalogCategories.TOP_RATED_LIMIT) { index?.ratingScore(it) }
            else -> {
                val parsed = StreamingServices.parseCategoryId(categoryId)
                if (parsed != null) {
                    val grouped = StreamingServices.group(all, extra = { index?.lookup(it) ?: emptySet() })
                    grouped[parsed].orEmpty()
                } else {
                    all.filter { it.categoryId == categoryId }
                }
            }
        }
    }

    /**
     * Movies / Series sort inside the selected group. "Newly added" and "Top rated" keep their
     * own order for A–Z / Z–A / Most recent (unchanged). The Top rated sort ranks any group,
     * including those two, with the Top rated group's rules: TMDB scores only (min votes)
     * when TMDB lookups are on, otherwise provider ratings; unrated titles go last.
     */
    fun sortBrowse(
        items: List<MediaItem>,
        categoryId: String?,
        sort: com.totaliptv.pro.desktop.ui.BrowseSort,
        index: ServiceIndex?
    ): List<MediaItem> {
        if (sort == com.totaliptv.pro.desktop.ui.BrowseSort.TOP) {
            return try {
                Ratings.sortTopRated(items, index?.let { idx -> { item: MediaItem -> idx.ratingScore(item) } })
            } catch (t: Exception) {
                com.totaliptv.pro.desktop.artwork.RatingOrder.logFailure(t)
                items
            }
        }
        if (CatalogCategories.isOrderedVirtual(categoryId)) return items
        return when (sort) {
            com.totaliptv.pro.desktop.ui.BrowseSort.AZ ->
                com.totaliptv.pro.desktop.artwork.RatingOrder.sortByTitle(items, descending = false)
            com.totaliptv.pro.desktop.ui.BrowseSort.ZA ->
                com.totaliptv.pro.desktop.artwork.RatingOrder.sortByTitle(items, descending = true)
            com.totaliptv.pro.desktop.ui.BrowseSort.RECENT ->
                com.totaliptv.pro.desktop.artwork.RatingOrder.sortRecent(items)
            com.totaliptv.pro.desktop.ui.BrowseSort.TOP -> items
        }
    }

    fun newlyAdded(items: List<MediaItem>, limit: Int = NEWLY_ADDED_LIMIT): List<MediaItem> =
        sortByRecentlyAdded(items).take(limit)

    /**
     * 1) [MediaItem.addedEpoch] when any item has it
     * 2) else descending [MediaItem.xtreamStreamId]
     * 3) else reverse catalog order
     */
    fun sortByRecentlyAdded(items: List<MediaItem>): List<MediaItem> {
        val anyAdded = items.any { it.addedEpoch > 0L }
        if (anyAdded) {
            return items.sortedWith(
                compareByDescending<MediaItem> { it.addedEpoch }
                    .thenByDescending { it.xtreamStreamId ?: 0 }
            )
        }
        val anySid = items.any { (it.xtreamStreamId ?: 0) > 0 }
        if (anySid) {
            return items.sortedByDescending { it.xtreamStreamId ?: 0 }
        }
        return items.asReversed()
    }
}
