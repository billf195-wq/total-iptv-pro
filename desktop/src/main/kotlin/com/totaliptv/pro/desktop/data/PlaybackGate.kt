package com.totaliptv.pro.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * True while an external player (VLC / mpv / ffplay, including Game Day) is running.
 * Background TMDB work must wait until this is false.
 */
object PlaybackGate {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun setPlaying(playing: Boolean) {
        _active.value = playing
    }

    fun refreshAllowed(): Boolean = !_active.value

    internal fun resetForTest() {
        _active.value = false
    }
}
