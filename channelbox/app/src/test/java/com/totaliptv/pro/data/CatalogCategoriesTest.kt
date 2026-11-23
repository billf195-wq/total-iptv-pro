package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogCategoriesTest {
    private val provider = listOf(
        Category("vod-1", "Action", ContentKind.VOD),
        Category("vod-2", "Drama", ContentKind.VOD)
    )

    private fun svc(s: StreamingService) = Category(StreamingServices.categoryId(s, ContentKind.VOD), s.label, ContentKind.VOD)

    @Test
    fun orderIsNewlyTopRatedServicesThenProvider() {
        val services = listOf(
            svc(StreamingService.APPLE), svc(StreamingService.NETFLIX), svc(StreamingService.HBO),
            svc(StreamingService.PRIME), svc(StreamingService.PARAMOUNT), svc(StreamingService.HULU),
            svc(StreamingService.DISNEY)
        )
        val names = CatalogCategories.build(ContentKind.VOD, provider, hasItems = true, hasRated = true, serviceCategories = services)
            .map { it.name }
        assertEquals(
            listOf(
                "Newly added", "Top rated", "Netflix", "Prime Video", "HBO / Max", "Apple TV+",
                "Hulu", "Disney+", "Paramount+", "Action", "Drama"
            ),
            names
        )
    }

    @Test
    fun emptyServiceGroupsAndNoRatingsAreHidden() {
        val names = CatalogCategories.build(ContentKind.VOD, provider, hasItems = true, hasRated = false,
            serviceCategories = listOf(svc(StreamingService.HBO))).map { it.name }
        assertEquals(listOf("Newly added", "HBO / Max", "Action", "Drama"), names)
        assertEquals(provider, CatalogCategories.build(ContentKind.VOD, provider, false, true, emptyList()))
    }

    @Test
    fun providerVirtualIdsAreNotDuplicated() {
        val withVirtual = listOf(Category(CatalogCategories.newlyAddedId(ContentKind.SERIES), "Newly added", ContentKind.SERIES),
            Category("series-9", "Comedy", ContentKind.SERIES))
        val out = CatalogCategories.build(ContentKind.SERIES, withVirtual, true, true, emptyList())
        assertEquals(listOf("Newly added", "Top rated", "Comedy"), out.map { it.name })
        assertTrue(CatalogCategories.isTopRatedId(out[1].id))
        assertTrue(CatalogCategories.isOrderedVirtual(out[0].id))
    }

    @Test
    fun seeAllHelpersAndDefaultBrowseStayOffTheFullList() {
        assertEquals("vod-all", CatalogCategories.allId(ContentKind.VOD))
        assertEquals("series-all", CatalogCategories.allId(ContentKind.SERIES))
        assertEquals("See all", CatalogCategories.ALL_NAME)
        assertEquals(20, CatalogCategories.HOME_ROW_LIMIT)
        assertTrue(CatalogCategories.isAllId(CatalogCategories.ALL_VOD_ID))
        assertTrue(CatalogCategories.isAllId(CatalogCategories.ALL_SERIES_ID))
        assertFalse(CatalogCategories.isAllId(CatalogCategories.NEWLY_ADDED_VOD_ID))
        // Virtual All is not inserted into build(), so Desktop's existing All chip stays unique
        // and the disk cache is unchanged.
        val built = CatalogCategories.build(ContentKind.VOD, provider, true, true, emptyList())
        assertEquals(listOf("Newly added", "Top rated", "Action", "Drama"), built.map { it.name })
        assertTrue(built.none { CatalogCategories.isAllId(it.id) })
        assertEquals(CatalogCategories.NEWLY_ADDED_VOD_ID, CatalogCategories.defaultBrowseId(built))
        assertTrue(CatalogCategories.isKnownBrowseId(CatalogCategories.ALL_VOD_ID, built, favCategoryId = "fav-movies"))
        assertTrue(CatalogCategories.isKnownBrowseId("vod-1", built, favCategoryId = "fav-movies"))
        assertFalse(CatalogCategories.isKnownBrowseId("missing", built, favCategoryId = "fav-movies"))
        val preview = CatalogCategories.previewPlusHasMore((1..25).map {
            MediaItem(id = "m$it", name = "m$it", streamUrl = "", categoryId = null, kind = ContentKind.VOD)
        })
        assertEquals(20, preview.first.size)
        assertTrue(preview.second)
        val short = CatalogCategories.previewPlusHasMore((1..5).map {
            MediaItem(id = "s$it", name = "s$it", streamUrl = "", categoryId = null, kind = ContentKind.VOD)
        })
        assertEquals(5, short.first.size)
        assertFalse(short.second)
    }

    @Test
    fun cacheFileNameIsUnchangedForInPlaceUpdates() {
        assertEquals("vod-catalog-cache.json", com.totaliptv.pro.data.VodDiskCache.FILE_NAME)
    }

        @Test
    fun topRatedOnlyKeepsRatedHighestFirstAndCaps() {
        fun m(n: String, r: String?) = MediaItem(id = n, name = n, streamUrl = "", categoryId = null, kind = ContentKind.VOD, rating = r)
        val items = listOf(m("b", "7.0"), m("a", "9.1"), m("c", null), m("d", "7.0"), m("e", "8.4"))
        assertEquals(listOf("a", "e", "b", "d"), Ratings.topRatedOnly(items, 500).map { it.name })
        assertEquals(listOf("a", "e"), Ratings.topRatedOnly(items, 2).map { it.name })
        assertEquals(500, CatalogCategories.TOP_RATED_LIMIT)
    }
}
