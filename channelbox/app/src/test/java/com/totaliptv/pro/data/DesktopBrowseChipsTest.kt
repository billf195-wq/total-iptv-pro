package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopBrowseChipsTest {
    private fun cat(id: String, name: String, kind: ContentKind = ContentKind.VOD) =
        Category(id = id, name = name, kind = kind)

    @Test
    fun moviesPlacesVisibleAllImmediatelyAfterFavorites() {
        val categories = listOf(
            FavoriteSections.category(ContentKind.VOD),
            cat(CatalogCategories.NEWLY_ADDED_VOD_ID, "Newly added"),
            cat(CatalogCategories.TOP_RATED_VOD_ID, "Top rated"),
            cat("svc-netflix", "Netflix"),
            cat("provider-1", "Action")
        )
        val items = DesktopBrowseChips.items(categories)
        assertEquals(
            listOf(
                FavoriteSections.MOVIES_CATEGORY_ID to "Favorites",
                null to "All",
                CatalogCategories.NEWLY_ADDED_VOD_ID to "Newly added",
                CatalogCategories.TOP_RATED_VOD_ID to "Top rated",
                "svc-netflix" to "Netflix",
                "provider-1" to "Action"
            ),
            items
        )
        assertEquals(1, items.count { it.second == "All" })
        assertEquals(1, items.indexOfFirst { it.first == null && it.second == "All" })
    }

    @Test
    fun dropsHiddenOrDuplicateAllAndSeeAll() {
        val categories = listOf(
            FavoriteSections.category(ContentKind.SERIES),
            cat(CatalogCategories.NEWLY_ADDED_SERIES_ID, "Newly added"),
            cat(CatalogCategories.ALL_SERIES_ID, CatalogCategories.ALL_NAME),
            cat("dup-all", "All"),
            cat("provider-1", "Drama")
        )
        val items = DesktopBrowseChips.items(categories)
        assertEquals(
            listOf(
                FavoriteSections.SERIES_CATEGORY_ID to "Favorites",
                null to "All",
                CatalogCategories.NEWLY_ADDED_SERIES_ID to "Newly added",
                "provider-1" to "Drama"
            ),
            items
        )
        assertEquals(1, items.count { DesktopBrowseChips.isAllChip(it.first, it.second) })
    }

    @Test
    fun allIsFirstWhenFavoritesIsMissing() {
        val categories = listOf(
            cat(CatalogCategories.NEWLY_ADDED_VOD_ID, "Newly added"),
            cat("provider-1", "Action")
        )
        val items = DesktopBrowseChips.items(categories)
        assertEquals(null to "All", items.first())
        assertEquals("Newly added", items[1].second)
    }

    @Test
    fun liveKeepsAllFirst() {
        val categories = listOf(
            FavoriteSections.category(ContentKind.LIVE),
            cat("live-news", "News", ContentKind.LIVE)
        )
        val items = DesktopBrowseChips.liveItems(categories)
        assertEquals(null to "All", items.first())
        assertEquals(LiveFavorites.CATEGORY_ID to "Favorites", items[1])
        assertEquals("News", items[2].second)
        assertEquals(1, items.count { it.second == "All" })
    }

    @Test
    fun allChipUsesNullIdSoGridShowsEveryTitle() {
        val all = DesktopBrowseChips.items(listOf(FavoriteSections.category(ContentKind.VOD))).single { it.second == "All" }
        assertEquals(null, all.first)
        assertFalse(DesktopBrowseChips.isAllChip(FavoriteSections.MOVIES_CATEGORY_ID, "Favorites"))
        assertTrue(DesktopBrowseChips.isAllChip(null, "All"))
        assertTrue(DesktopBrowseChips.isAllChip(CatalogCategories.ALL_VOD_ID, "See all"))
    }
}
