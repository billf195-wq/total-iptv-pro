package com.totaliptv.pro.desktop.data

import com.totaliptv.pro.desktop.data.tmdb.ServiceIndex
import com.totaliptv.pro.desktop.data.tmdb.TmdbServiceEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowseCatalogTest {
    private fun movie(id: String, name: String, category: String = "vod-action", added: Long = 0, sid: Int? = null) =
        MediaItem(id = id, name = name, streamUrl = "http://x/$id", categoryId = category,
            kind = ContentKind.VOD, addedEpoch = added, xtreamStreamId = sid)

    @Test
    fun defaultOrderIsNewlyAddedThenTopRatedThenServicesThenProvider() {
        val catalog = Catalog(
            vodCategories = listOf(Category("vod-action", "Action", ContentKind.VOD)),
            vodItems = listOf(
                movie("1", "Old", added = 1),
                movie("2", "The Irishman", added = 2)
            )
        )
        val index = ServiceIndex(
            listOf(
                TmdbServiceEntry("movie", 1, "The Irishman", year = 2019, services = listOf("netflix"),
                    voteAverage = 8.2, voteCount = 200)
            )
        )
        val cats = BrowseCatalog.categories(ContentKind.VOD, catalog, index)
        assertEquals(
            listOf("Newly added", "Top rated", "Netflix", "Action"),
            cats.map { it.name }
        )
        assertEquals(CatalogCategories.NEWLY_ADDED_VOD_ID, BrowseCatalog.defaultCategoryId(ContentKind.VOD, cats))
        val newest = BrowseCatalog.items(ContentKind.VOD, catalog, CatalogCategories.NEWLY_ADDED_VOD_ID, index)
        assertEquals("The Irishman", newest.first().name)
        val netflix = BrowseCatalog.items(ContentKind.VOD, catalog, StreamingServices.categoryId(StreamingService.NETFLIX, ContentKind.VOD), index)
        assertTrue(netflix.any { it.name == "The Irishman" })
    }

    @Test
    fun topRatedRequiresTmdbVotes() {
        val catalog = Catalog(
            vodItems = listOf(movie("1", "The Irishman"))
        )
        val unrated = BrowseCatalog.categories(ContentKind.VOD, catalog, index = null)
        assertTrue(unrated.none { CatalogCategories.isTopRatedId(it.id) })
        val index = ServiceIndex(
            listOf(TmdbServiceEntry("movie", 1, "The Irishman", year = 2019, services = listOf("netflix"),
                voteAverage = 8.2, voteCount = 200))
        )
        val rated = BrowseCatalog.items(ContentKind.VOD, catalog, CatalogCategories.TOP_RATED_VOD_ID, index)
        assertEquals(listOf("The Irishman"), rated.map { it.name })
    }

    private fun rated(id: String, name: String, rating: String?, category: String = "vod-action") =
        movie(id, name, category).copy(rating = rating)

    @Test
    fun topRatedSortRanksAllWithProviderRatingsWhenNoTmdb() {
        val items = listOf(
            rated("1", "Alpha", null),
            rated("2", "Bravo", "6.1"),
            rated("3", "Charlie", "8.4"),
            rated("4", "Delta", "7.0")
        )
        val out = BrowseCatalog.sortBrowse(items, null, com.totaliptv.pro.desktop.ui.BrowseSort.TOP, null)
        assertEquals(listOf("Charlie", "Delta", "Bravo", "Alpha"), out.map { it.name })
    }

    @Test
    fun topRatedSortRanksInsideServiceGroupWithTmdbOnly() {
        val catalog = Catalog(
            vodCategories = listOf(Category("vod-action", "Action", ContentKind.VOD)),
            vodItems = listOf(
                rated("1", "The Irishman", "9.9"),
                rated("2", "Roma", "1.0"),
                rated("3", "Obscure", "9.0")
            )
        )
        val index = ServiceIndex(
            listOf(
                TmdbServiceEntry("movie", 1, "The Irishman", year = 2019, services = listOf("netflix"),
                    voteAverage = 7.8, voteCount = 200),
                TmdbServiceEntry("movie", 2, "Roma", year = 2018, services = listOf("netflix"),
                    voteAverage = 8.1, voteCount = 300),
                TmdbServiceEntry("movie", 3, "Obscure", year = 2018, services = listOf("netflix"),
                    voteAverage = 9.5, voteCount = 10)
            )
        )
        val netflixId = StreamingServices.categoryId(StreamingService.NETFLIX, ContentKind.VOD)
        val base = BrowseCatalog.items(ContentKind.VOD, catalog, netflixId, index)
        val out = BrowseCatalog.sortBrowse(base, netflixId, com.totaliptv.pro.desktop.ui.BrowseSort.TOP, index)
        // TMDB only: provider 9.9 / 9.0 ignored, Obscure has too few votes so it goes last.
        assertEquals(listOf("Roma", "The Irishman", "Obscure"), out.map { it.name })
    }

    @Test
    fun orderedGroupsKeepOwnOrderForOtherSortsButRankForTopRated() {
        val items = listOf(rated("1", "Zed", "5.0"), rated("2", "Abe", "9.0"))
        val newly = CatalogCategories.NEWLY_ADDED_VOD_ID
        assertEquals(listOf("Zed", "Abe"),
            BrowseCatalog.sortBrowse(items, newly, com.totaliptv.pro.desktop.ui.BrowseSort.AZ, null).map { it.name })
        assertEquals(listOf("Abe", "Zed"),
            BrowseCatalog.sortBrowse(items, newly, com.totaliptv.pro.desktop.ui.BrowseSort.TOP, null).map { it.name })
        assertEquals(listOf("Abe", "Zed"),
            BrowseCatalog.sortBrowse(items, "vod-action", com.totaliptv.pro.desktop.ui.BrowseSort.AZ, null).map { it.name })
    }

    @Test
    fun topRatedSortPersistsAndIsAfterMostRecent() {
        val entries = com.totaliptv.pro.desktop.ui.BrowseSort.entries.map { it.label }
        assertEquals(listOf("A–Z", "Z–A", "Most recent", "Top rated"), entries)
        assertEquals(com.totaliptv.pro.desktop.ui.BrowseSort.TOP, com.totaliptv.pro.desktop.ui.BrowseSort.fromPrefs("TOP"))
    }
}
