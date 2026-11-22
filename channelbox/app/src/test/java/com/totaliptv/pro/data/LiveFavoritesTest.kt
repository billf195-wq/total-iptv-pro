package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.FavoriteRef
import com.totaliptv.pro.data.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveFavoritesTest {

    private fun live(id: Int, name: String, cat: String = "live-usa"): MediaItem = MediaItem(
        id = "live-$id",
        name = name,
        streamUrl = "http://example.test/live/u/p/$id.ts",
        categoryId = cat,
        kind = ContentKind.LIVE,
        xtreamStreamId = id,
        categoryIds = listOf(cat)
    )

    private fun fav(item: MediaItem) = FavoriteRef(
        id = item.id,
        name = item.name,
        streamUrl = item.streamUrl,
        kind = item.kind,
        logoUrl = item.logoUrl
    )

    @Test
    fun favoritesCategoryIsFirstAndNotDuplicated() {
        val provider = listOf(
            Category("live-usa", "USA", ContentKind.LIVE),
            Category("live-news", "News", ContentKind.LIVE)
        )
        val cats = LiveFavorites.withFavoritesCategory(provider)
        assertEquals(LiveFavorites.CATEGORY_ID, cats.first().id)
        assertEquals("Favorites", cats.first().name)
        assertEquals(listOf("live-favorites", "live-usa", "live-news"), cats.map { it.id })
        val again = LiveFavorites.withFavoritesCategory(cats)
        assertEquals(1, again.count { it.id == LiveFavorites.CATEGORY_ID })
    }

    @Test
    fun defaultOpensFavoritesOnlyWhenSomeAreSaved() {
        val cats = LiveFavorites.withFavoritesCategory(
            listOf(Category("live-usa", "USA", ContentKind.LIVE))
        )
        assertEquals("live-usa", LiveFavorites.defaultCategoryId(cats, liveFavoriteCount = 0))
        assertEquals(LiveFavorites.CATEGORY_ID, LiveFavorites.defaultCategoryId(cats, liveFavoriteCount = 2))
    }

    @Test
    fun liveFavoritesAreOnlyLiveAndKeepSavedOrder() {
        val cnn = live(100, "CNN")
        val bet = live(200, "BET")
        val fox = live(300, "Fox")
        val movieFav = FavoriteRef("vod-1", "A Movie", "http://example.test/m", ContentKind.VOD, null)
        val seriesFav = FavoriteRef("series-1", "A Show", "http://example.test/s", ContentKind.SERIES, null)
        val favorites = listOf(fav(bet), movieFav, fav(fox), seriesFav, fav(cnn))
        val result = LiveFavorites.channels(listOf(cnn, bet, fox), favorites)
        assertEquals(listOf("BET", "Fox", "CNN"), result.map { it.name })
        assertTrue(result.all { it.kind == ContentKind.LIVE })
    }

    @Test
    fun movieFavoritesDoNotAppearInTheLiveFavoritesCategory() {
        val cnn = live(100, "CNN")
        val favorites = listOf(
            FavoriteRef("vod-9", "Movie", "http://example.test/m", ContentKind.VOD, null)
        )
        assertTrue(LiveFavorites.channels(listOf(cnn), favorites).isEmpty())
        assertEquals(0, LiveFavorites.liveFavoriteCount(favorites))
    }

    @Test
    fun filterLiveUsesFavoritesCategoryAndLeavesOtherGroupsAlone() {
        val cnn = live(100, "CNN", "live-news")
        val bet = live(200, "BET", "live-usa")
        val live = listOf(cnn, bet)
        val favorites = listOf(fav(bet))
        val favs = LiveFavorites.filterLive(live, favorites, LiveFavorites.CATEGORY_ID)
        assertEquals(listOf("BET"), favs.map { it.name })
        val news = LiveFavorites.filterLive(live, favorites, "live-news")
        assertEquals(listOf("CNN"), news.map { it.name })
        assertFalse(LiveFavorites.isFavoritesCategory("live-news"))
    }

    @Test
    fun staleFavoriteStillAppearsUsingTheSavedName() {
        val favorites = listOf(
            FavoriteRef("live-999", "Old Channel", "http://example.test/gone", ContentKind.LIVE, null)
        )
        val result = LiveFavorites.channels(liveItems = emptyList(), favorites = favorites)
        assertEquals(listOf("Old Channel"), result.map { it.name })
        assertEquals("live-999", result.single().id)
    }
}
