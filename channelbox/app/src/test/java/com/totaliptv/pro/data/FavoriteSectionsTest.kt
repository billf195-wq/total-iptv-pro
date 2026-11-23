package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.FavoriteRef
import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoriteSectionsTest {
    private fun fav(id: String, kind: ContentKind) = FavoriteRef(
        id = id,
        name = id,
        streamUrl = "",
        kind = kind,
        logoUrl = null
    )

    @Test
    fun groupsKeepSavedOrderAndDoNotDropTitles() {
        val saved = listOf(
            fav("movie-a", ContentKind.VOD),
            fav("live-1", ContentKind.LIVE),
            fav("series-b", ContentKind.SERIES),
            fav("movie-c", ContentKind.VOD),
            fav("live-2", ContentKind.LIVE)
        )
        assertEquals(listOf("live-1", "live-2"), FavoriteSections.live(saved).map { it.id })
        assertEquals(listOf("movie-a", "movie-c"), FavoriteSections.movies(saved).map { it.id })
        assertEquals(listOf("series-b"), FavoriteSections.series(saved).map { it.id })
        val all = FavoriteSections.live(saved) + FavoriteSections.movies(saved) + FavoriteSections.series(saved)
        assertEquals(saved.map { it.id }.toSet(), all.map { it.id }.toSet())
        assertEquals(saved.size, all.size)
    }

    @Test
    fun emptyListStaysEmpty() {
        val none = emptyList<FavoriteRef>()
        assertEquals(emptyList(), FavoriteSections.live(none))
        assertEquals(emptyList(), FavoriteSections.movies(none))
        assertEquals(emptyList(), FavoriteSections.series(none))
    }

    private fun ref(id: String, kind: ContentKind, url: String = "") =
        FavoriteRef(id = id, name = "n-$id", streamUrl = url, kind = kind, logoUrl = "logo-$id")

    @Test
    fun mixedOlderFavoritesSortByIdOrStreamPathAndNothingIsLost() {
        // Older lists can carry the wrong kind (e.g. saved from the player); the id wins.
        val saved = listOf(
            ref("series-42", ContentKind.VOD),
            ref("vod-7", ContentKind.SERIES),
            ref("live-3", ContentKind.VOD),
            ref("series-ep-900", ContentKind.VOD),
            ref("m3u-a", ContentKind.LIVE, "http://h.test/movie/u/p/11.mkv"),
            ref("m3u-b", ContentKind.LIVE, "http://h.test/series/u/p/12.mp4"),
            ref("m3u-c", ContentKind.LIVE, "http://h.test/u/p/13"),
            ref("m3u-d", ContentKind.VOD, "")
        )
        assertEquals(listOf("vod-7", "m3u-a", "m3u-d"), FavoriteSections.movies(saved).map { it.id })
        assertEquals(listOf("series-42", "series-ep-900", "m3u-b"), FavoriteSections.series(saved).map { it.id })
        assertEquals(listOf("live-3", "m3u-c"), FavoriteSections.live(saved).map { it.id })
        val all = FavoriteSections.live(saved) + FavoriteSections.movies(saved) + FavoriteSections.series(saved)
        assertEquals(saved.size, all.size)
        assertEquals(saved.map { it.id }.toSet(), all.map { it.id }.toSet())
        assertEquals(3, FavoriteSections.count(saved, ContentKind.VOD))
        assertEquals(2, LiveFavorites.liveFavoriteCount(saved))
    }

    @Test
    fun moviesAndSeriesGetTheirOwnFavoritesRowFirst() {
        val movieCats = listOf(Category("vod-1", "Action", ContentKind.VOD), Category("vod-2", "Drama", ContentKind.VOD))
        val m = FavoriteSections.withFavoritesCategory(ContentKind.VOD, movieCats)
        assertEquals(FavoriteSections.MOVIES_CATEGORY_ID, m.first().id)
        assertEquals("Favorites", m.first().name)
        assertEquals(ContentKind.VOD, m.first().kind)
        assertEquals(3, m.size)
        assertEquals(m, FavoriteSections.withFavoritesCategory(ContentKind.VOD, m))
        val s = FavoriteSections.withFavoritesCategory(ContentKind.SERIES, emptyList())
        assertEquals(listOf(FavoriteSections.SERIES_CATEGORY_ID), s.map { it.id })
        assertTrue(FavoriteSections.onlyFavorites(s))
        assertFalse(FavoriteSections.onlyFavorites(m))
        val l = FavoriteSections.withFavoritesCategory(ContentKind.LIVE, emptyList())
        assertEquals(LiveFavorites.CATEGORY_ID, l.first().id)
        listOf(FavoriteSections.MOVIES_CATEGORY_ID, FavoriteSections.SERIES_CATEGORY_ID, LiveFavorites.CATEGORY_ID)
            .forEach { assertTrue(FavoriteSections.isFavoritesCategory(it)) }
        assertFalse(FavoriteSections.isFavoritesCategory("vod-1"))
        // Our ids never collide with provider group ids ("vod-<n>", "series-<n>", "live-<n>").
        assertFalse(FavoriteSections.MOVIES_CATEGORY_ID.startsWith("vod-"))
        assertFalse(FavoriteSections.SERIES_CATEGORY_ID.startsWith("series-"))
    }

    @Test
    fun defaultOpensFavoritesOnlyWhenThatKindHasFavorites() {
        val cats = FavoriteSections.withFavoritesCategory(
            ContentKind.SERIES, listOf(Category("series-5", "Drama", ContentKind.SERIES))
        )
        val onlyMovie = listOf(ref("vod-1", ContentKind.VOD))
        assertEquals("series-5", FavoriteSections.defaultCategoryId(ContentKind.SERIES, cats, onlyMovie))
        val withSeries = onlyMovie + ref("series-9", ContentKind.SERIES)
        assertEquals(FavoriteSections.SERIES_CATEGORY_ID, FavoriteSections.defaultCategoryId(ContentKind.SERIES, cats, withSeries))
        val lone = FavoriteSections.withFavoritesCategory(ContentKind.SERIES, emptyList())
        assertEquals(FavoriteSections.SERIES_CATEGORY_ID, FavoriteSections.defaultCategoryId(ContentKind.SERIES, lone, emptyList()))
    }

    @Test
    fun itemsPreferCatalogRowKeepSavedOrderAndFallBackToSavedRef() {
        val cat = listOf(
            MediaItem(id = "vod-2", name = "Two (catalog)", streamUrl = "u2", categoryId = "vod-1",
                kind = ContentKind.VOD, posterUrl = "p2", rating = "8.1"),
            MediaItem(id = "series-2", name = "Not a movie", streamUrl = "s", categoryId = "series-1",
                kind = ContentKind.SERIES)
        )
        val saved = listOf(
            ref("vod-9", ContentKind.VOD),
            ref("series-2", ContentKind.SERIES),
            ref("vod-2", ContentKind.VOD),
            ref("vod-9", ContentKind.VOD)
        )
        val movies = FavoriteSections.items(ContentKind.VOD, saved, cat)
        assertEquals(listOf("vod-9", "vod-2"), movies.map { it.id })
        assertEquals("Two (catalog)", movies[1].name)
        assertEquals("8.1", movies[1].rating)
        assertEquals("n-vod-9", movies[0].name)
        assertEquals("logo-vod-9", movies[0].posterUrl)
        assertEquals(ContentKind.VOD, movies[0].kind)
        val series = FavoriteSections.items(ContentKind.SERIES, saved, cat)
        assertEquals(listOf("series-2"), series.map { it.id })
        assertEquals(emptyList(), FavoriteSections.items(ContentKind.LIVE, saved, cat))
        assertTrue(FavoriteSections.isEpisode("series-ep-1"))
        assertFalse(FavoriteSections.isEpisode("series-1"))
    }

    @Test
    fun classicSidebarsHaveAFavoritesRowPerKind() {
        val src = java.io.File("src/tv/java/com/totaliptv/pro/ui/home/HomeScreen.kt").readText()
        // Live: Quick > Favorites now opens in place and counts only channels.
        assertTrue(src.contains("count = FavoriteSections.count(favorites, ContentKind.LIVE)"))
        assertTrue(src.contains("onClick = { selectedCategoryId = LiveFavorites.CATEGORY_ID }"))
        assertTrue(src.contains("LiveFavorites.channels(repository.liveItems(), favorites)"))
        // Movies / Series: own Favorites row above the categories, only that kind.
        assertTrue(src.contains("count = FavoriteSections.count(favorites, browseKind ?: ContentKind.VOD)"))
        assertTrue(src.contains("FavoriteSections.items(kind, favorites, repository.itemsByKind(kind))"))
        assertTrue(src.contains("CatalogCategories.isKnownBrowseId"))
        assertTrue(src.contains("CatalogCategories.ALL_NAME"))
        assertTrue(src.contains("CatalogCategories.allId"))
        assertTrue(src.contains("CatalogCategories.defaultBrowseId"))
        assertFalse(src.contains("count = favorites.size"))
        assertFalse(src.contains("CHIP_KINDS"))
    }
}
