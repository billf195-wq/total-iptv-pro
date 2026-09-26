package com.totaliptv.pro.artwork

import android.app.Application

/** Phone builds do not load sharp posters or call TMDB. */
object ArtworkFlavor {
    fun install(app: Application) = Unit
}
