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
}
