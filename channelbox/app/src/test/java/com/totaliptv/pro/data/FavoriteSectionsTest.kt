package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.FavoriteRef
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
