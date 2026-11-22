package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.FavoriteRef

/** Splits the saved favorites list into Live / Movies / Series, keeping each group in saved order. */
object FavoriteSections {
    fun live(favorites: List<FavoriteRef>): List<FavoriteRef> =
        favorites.filter { it.kind == ContentKind.LIVE }

    fun movies(favorites: List<FavoriteRef>): List<FavoriteRef> =
        favorites.filter { it.kind == ContentKind.VOD }

    fun series(favorites: List<FavoriteRef>): List<FavoriteRef> =
        favorites.filter { it.kind == ContentKind.SERIES }
}
