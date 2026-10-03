package com.totaliptv.pro.ui.nav

/**
 * Back from a section returns home. Back on home asks once, then exits.
 */
object TvBack {
    const val EXIT_WINDOW_MS = 2_000L

    enum class Action { GO_HOME, ARM_EXIT, EXIT }

    fun action(atHome: Boolean, armedAtMs: Long, nowMs: Long): Action {
        if (!atHome) return Action.GO_HOME
        if (armedAtMs > 0L && nowMs - armedAtMs <= EXIT_WINDOW_MS) return Action.EXIT
        return Action.ARM_EXIT
    }
}
