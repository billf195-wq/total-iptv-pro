package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind

/**
 * Movies / Series category order:
 * Newly added, Top rated, Netflix, Prime Video, HBO / Max, Apple TV+, then provider groups
 * (unchanged order). Pure helper so the order is unit-tested.
 */
object CatalogCategories {
    const val NEWLY_ADDED_VOD_ID = "vod-newly-added"
    const val NEWLY_ADDED_SERIES_ID = "series-newly-added"
    const val NEWLY_ADDED_NAME = "Newly added"
    const val TOP_RATED_VOD_ID = "vod-top-rated"
    const val TOP_RATED_SERIES_ID = "series-top-rated"
    const val TOP_RATED_NAME = "Top rated"
    const val TOP_RATED_LIMIT = 500

    fun newlyAddedId(kind: ContentKind): String =
        if (kind == ContentKind.SERIES) NEWLY_ADDED_SERIES_ID else NEWLY_ADDED_VOD_ID

    fun topRatedId(kind: ContentKind): String =
        if (kind == ContentKind.SERIES) TOP_RATED_SERIES_ID else TOP_RATED_VOD_ID

    fun isTopRatedId(id: String?): Boolean = id == TOP_RATED_VOD_ID || id == TOP_RATED_SERIES_ID
    fun isNewlyAddedId(id: String?): Boolean = id == NEWLY_ADDED_VOD_ID || id == NEWLY_ADDED_SERIES_ID

    /** Virtual lists that already come in a meaningful order (keep it for the default sort). */
    fun isOrderedVirtual(id: String?): Boolean = isTopRatedId(id) || isNewlyAddedId(id)

    fun isVirtual(id: String?): Boolean =
        isOrderedVirtual(id) || StreamingServices.isServiceCategoryId(id)

    /**
     * @param provider provider categories for [kind] in provider order (virtual ids are dropped).
     * @param hasItems any titles of [kind] loaded (no virtual groups when false).
     * @param hasRated any rated titles of [kind] (hides Top rated when none).
     * @param serviceCategories non-empty service groups (any order; re-sorted to service order).
     */
    fun build(
        kind: ContentKind,
        provider: List<Category>,
        hasItems: Boolean,
        hasRated: Boolean,
        serviceCategories: List<Category>
    ): List<Category> {
        val base = provider.filter { !isVirtual(it.id) }
        if (!hasItems) return base
        val out = ArrayList<Category>(base.size + 6)
        out += Category(newlyAddedId(kind), NEWLY_ADDED_NAME, kind)
        if (hasRated) out += Category(topRatedId(kind), TOP_RATED_NAME, kind)
        for (svc in StreamingService.entries) {
            val id = StreamingServices.categoryId(svc, kind)
            serviceCategories.firstOrNull { it.id == id }?.let { out += it }
        }
        out += base
        return out
    }
}
