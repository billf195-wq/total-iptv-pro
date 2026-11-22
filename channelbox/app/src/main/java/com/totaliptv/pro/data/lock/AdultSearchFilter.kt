package com.totaliptv.pro.data.lock

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.MediaItem

/**
 * Search-only adult filter. Uses the same automatic adult-category names as the PIN lock,
 * plus a conservative title-keyword check. Title matching deliberately skips a bare "xxx"
 * so a mainstream movie titled "xXx" is not hidden.
 */
object AdultSearchFilter {
    fun isAdultTitle(name: String?): Boolean = AdultCategories.isAdultTitle(name)

    fun isAdultCategory(category: Category?): Boolean =
        category != null && AdultCategories.isAdult(category.name)

    fun shouldHide(item: MediaItem, categoriesById: Map<String, Category>): Boolean {
        if (isAdultTitle(item.name)) return true
        val ids = LinkedHashSet<String>()
        item.categoryId?.let { ids += it }
        ids.addAll(item.categoryIds)
        return ids.any { id -> isAdultCategory(categoriesById[id]) }
    }

    fun filter(
        items: List<MediaItem>,
        categoriesById: Map<String, Category>,
        showAdultInSearch: Boolean
    ): List<MediaItem> =
        if (showAdultInSearch) items else items.filterNot { shouldHide(it, categoriesById) }
}
