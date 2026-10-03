package com.totaliptv.pro.data

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * True while any in-app player (full screen or split) is on screen.
 * Background guide downloads wait for this to clear so they never compete with a stream.
 */
object PlaybackGate {
    private val count = AtomicInteger(0)
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun enter() {
        _active.value = count.incrementAndGet() > 0
    }

    fun exit() {
        val n = count.updateAndGet { (it - 1).coerceAtLeast(0) }
        _active.value = n > 0
    }

    /** Whether a background guide refresh may run now. */
    fun refreshAllowed(): Boolean = !_active.value

    internal fun resetForTest() {
        count.set(0)
        _active.value = false
    }
}
