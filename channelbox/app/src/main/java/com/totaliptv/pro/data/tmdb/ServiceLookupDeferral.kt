package com.totaliptv.pro.data.tmdb

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Remembers a service-group refresh that had to wait because a video was playing.
 * A Refresh tap (force=true) must still be forced when playback ends; otherwise a
 * still-"fresh" cache would swallow the tap.
 */
class ServiceLookupDeferral {
    private val deferred = AtomicBoolean(false)
    private val force = AtomicBoolean(false)

    fun mark(forceRequested: Boolean) {
        deferred.set(true)
        if (forceRequested) force.set(true)
    }

    /** True if a force refresh was requested and is still waiting. */
    fun forcePending(): Boolean = force.get()

    fun isDeferred(): Boolean = deferred.get()

    /**
     * If a refresh was waiting, returns whether it should run as forced and clears the flag.
     * Null means nothing was waiting.
     */
    fun consume(): Boolean? {
        if (!deferred.getAndSet(false)) return null
        return force.getAndSet(false)
    }

    fun pausedStatus(forceRequested: Boolean): String =
        if (forceRequested || force.get()) {
            PAUSED_FORCED
        } else {
            PAUSED
        }

    companion object {
        const val PAUSED_FORCED =
            "Refresh paused during playback; it will finish when the video ends"
        const val PAUSED = "Looking up streaming services… (paused during playback)"
        const val SAVE_FAILED =
            "Could not save service groups on this device. Try Refresh service groups again."
        const val LOOKUP_FAILED_PREFIX = "Service lookup failed"
    }
}
