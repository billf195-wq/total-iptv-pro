package com.totaliptv.pro.artwork

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Process-wide artwork switches. TV settings write these. Phone never starts the rating engine. */
object ArtworkRuntime {
    private val sharpState = MutableStateFlow(true)
    private val ratingsState = MutableStateFlow(true)

    val sharp: StateFlow<Boolean> = sharpState
    val ratings: StateFlow<Boolean> = ratingsState

    @Volatile
    var apiKey: String = ""

    var sharpPosters: Boolean
        get() = sharpState.value
        set(value) { sharpState.value = value }

    var tmdbRatings: Boolean
        get() = ratingsState.value
        set(value) { ratingsState.value = value }

    fun ratingsActive(): Boolean = tmdbRatings && TmdbAuth.isUsable(apiKey)
}
