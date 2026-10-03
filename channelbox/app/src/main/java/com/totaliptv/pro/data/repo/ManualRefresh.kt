package com.totaliptv.pro.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Signals a user-initiated playlist reload (Settings refresh, Change source save,
 * Refresh data). The app root shows the startup LogoBannerSplash while one runs,
 * then returns to Home.
 */
object ManualRefresh {
    private val _epoch = MutableStateFlow(0)
    /** Bumps every time a manual reload starts. */
    val epoch: StateFlow<Int> = _epoch.asStateFlow()

    private val _active = MutableStateFlow(0)
    /** Number of manual reloads currently running. */
    val active: StateFlow<Int> = _active.asStateFlow()

    private val _home = MutableStateFlow(0)
    /** Bumps when the refresh splash finishes; roots navigate to Home. */
    val homeRequests: StateFlow<Int> = _home.asStateFlow()

    /** True while the refresh splash is on screen; activities swallow keys. */
    @Volatile
    var overlayVisible: Boolean = false

    fun begin() {
        _active.update { it + 1 }
        _epoch.update { it + 1 }
    }

    fun end() {
        _active.update { (it - 1).coerceAtLeast(0) }
    }

    fun requestHome() {
        _home.update { it + 1 }
    }
}
