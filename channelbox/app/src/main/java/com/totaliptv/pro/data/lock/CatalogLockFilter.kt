package com.totaliptv.pro.data.lock

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.MediaItem

/** Lock settings: [pinHash] null means the lock is off. */
data class LockConfig(
    val pinHash: String? = null,
    /** Categories the user ticked on top of the automatic adult ones. */
    val extraLocked: Set<String> = emptySet(),
    /** Automatic adult categories the user unticked. */
    val autoExempt: Set<String> = emptySet()
) {
    val enabled: Boolean get() = !pinHash.isNullOrBlank()

    fun isLocked(category: Category): Boolean =
        category.id in extraLocked ||
            (AdultCategories.isAdult(category.name) && category.id !in autoExempt)

    /** Tick / untick one category in the picker. */
    fun toggled(category: Category): LockConfig {
        val auto = AdultCategories.isAdult(category.name)
        return if (isLocked(category)) {
            if (auto) copy(autoExempt = autoExempt + category.id, extraLocked = extraLocked - category.id)
            else copy(extraLocked = extraLocked - category.id)
        } else {
            if (auto) copy(autoExempt = autoExempt - category.id)
            else copy(extraLocked = extraLocked + category.id)
        }
    }
}

object CatalogLockFilter {
    class Result(
        val categories: List<Category>,
        val items: List<MediaItem>,
        val hiddenItemIds: Set<String>,
        val lockedCategoryIds: Set<String>
    )

    fun lockedCategoryIds(categories: List<Category>, config: LockConfig): Set<String> =
        categories.asSequence().filter { config.isLocked(it) }.map { it.id }.toSet()

    fun isItemLocked(item: MediaItem, locked: Set<String>): Boolean {
        if (locked.isEmpty()) return false
        if (item.categoryId != null && item.categoryId in locked) return true
        return item.categoryIds.any { it in locked }
    }

    /** Visible catalog when the lock is active. Returns inputs untouched when nothing is locked. */
    fun apply(categories: List<Category>, items: List<MediaItem>, config: LockConfig): Result {
        val locked = lockedCategoryIds(categories, config)
        if (locked.isEmpty()) return Result(categories, items, emptySet(), emptySet())
        val hidden = HashSet<String>()
        val visible = ArrayList<MediaItem>(items.size)
        for (item in items) {
            if (isItemLocked(item, locked)) {
                hidden += item.id
                item.seriesCatalogId?.let { hidden += it }
            } else {
                visible += item
            }
        }
        return Result(categories.filter { it.id !in locked }, visible, hidden, locked)
    }
}
