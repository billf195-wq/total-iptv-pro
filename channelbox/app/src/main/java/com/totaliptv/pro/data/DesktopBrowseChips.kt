package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.Category

/**
 * Visible category chips on Desktop Movies and Series.
 *
 * Classic already shows Favorites, then See all. Desktop used to bury a single
 * "All" chip after every virtual and service group, so it sat off-screen.
 * One visible "All" (every title) now sits immediately after Favorites.
 * A hidden or duplicate All / See all entry is dropped, not added again.
 */
object DesktopBrowseChips {
    const val ALL_LABEL = "All"

    fun isAllChip(id: String?, name: String): Boolean {
        if (id == null) return true
        if (CatalogCategories.isAllId(id)) return true
        val trimmed = name.trim()
        return trimmed.equals(ALL_LABEL, ignoreCase = true) ||
            trimmed.equals(CatalogCategories.ALL_NAME, ignoreCase = true)
    }

    /**
     * Ordered chip entries for the Desktop Movies / Series filter row.
     * The All chip uses a null id so the grid shows every title.
     */
    fun items(categories: List<Category>): List<Pair<String?, String>> {
        val favorites = categories.filter { FavoriteSections.isFavoritesCategory(it.id) }
        val rest = categories.filterNot { category ->
            FavoriteSections.isFavoritesCategory(category.id) || isAllChip(category.id, category.name)
        }
        return buildList {
            favorites.forEach { add(it.id to it.name) }
            add(null to ALL_LABEL)
            rest.forEach { add(it.id to it.name) }
        }
    }

    /**
     * Live keeps All first, then the remaining groups (including Favorites
     * if the list starts with a non-virtual row).
     */
    fun liveItems(categories: List<Category>): List<Pair<String?, String>> {
        val withoutAll = categories.filterNot { isAllChip(it.id, it.name) }
        val leading = withoutAll.takeWhile { CatalogCategories.isVirtual(it.id) }
        val trailing = withoutAll.drop(leading.size)
        return buildList {
            leading.forEach { add(it.id to it.name) }
            add(null to ALL_LABEL)
            trailing.forEach { add(it.id to it.name) }
        }
    }
}
