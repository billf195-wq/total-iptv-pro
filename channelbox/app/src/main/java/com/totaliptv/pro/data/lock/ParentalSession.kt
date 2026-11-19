package com.totaliptv.pro.data.lock

/**
 * Session unlock. Starts locked (a fresh process is always locked). Unlock lasts until
 * [lockNow], or until the app has been in the background for [relockAfterMs].
 */
class ParentalSession(
    private val relockAfterMs: Long = RELOCK_AFTER_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    var unlocked: Boolean = false
        private set

    @Volatile
    private var backgroundAtMs: Long? = null

    fun unlock() {
        unlocked = true
    }

    fun lockNow() {
        unlocked = false
    }

    fun onBackground() {
        backgroundAtMs = clock()
    }

    /** Returns true when this call re-locked the session. */
    fun onForeground(): Boolean {
        val since = backgroundAtMs ?: return false
        backgroundAtMs = null
        if (unlocked && clock() - since >= relockAfterMs) {
            unlocked = false
            return true
        }
        return false
    }

    companion object {
        const val RELOCK_AFTER_MS: Long = 5 * 60_000L
    }
}
