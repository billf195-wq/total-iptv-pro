package com.totaliptv.pro.ui.nav

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager

/**
 * Back from a section returns home. Back on home asks once, then exits.
 * Keyboard Escape is treated as Back on TV. If the IME is up, the first
 * Back / Escape hides it and does not navigate.
 */
object TvBack {
    const val EXIT_WINDOW_MS = 2_000L
    const val KEYCODE_BACK = 4
    const val KEYCODE_ESCAPE = 111

    enum class Action { GO_HOME, ARM_EXIT, EXIT }

    fun isBackKey(keyCode: Int): Boolean =
        keyCode == KEYCODE_BACK || keyCode == KEYCODE_ESCAPE

    fun action(atHome: Boolean, armedAtMs: Long, nowMs: Long): Action {
        if (!atHome) return Action.GO_HOME
        if (armedAtMs > 0L && nowMs - armedAtMs <= EXIT_WINDOW_MS) return Action.EXIT
        return Action.ARM_EXIT
    }

    /**
     * Hide the IME when it is showing; otherwise return the same navigation
     * [action]. A null result means the event was consumed by dismissing the keyboard.
     */
    fun onBack(activity: Activity?, atHome: Boolean, armedAtMs: Long, nowMs: Long): Action? {
        if (activity != null && hideImeIfVisible(activity)) return null
        return action(atHome, armedAtMs, nowMs)
    }

    fun hideImeIfVisible(activity: Activity): Boolean {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return false
        val view: View = activity.currentFocus
            ?: activity.window?.decorView?.rootView
            ?: return false
        if (!imm.isActive(view) && !imm.isAcceptingText) return false
        return imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun asBackKeyEvent(event: android.view.KeyEvent): android.view.KeyEvent {
        if (event.keyCode != KEYCODE_ESCAPE) return event
        return android.view.KeyEvent(
            event.downTime,
            event.eventTime,
            event.action,
            KEYCODE_BACK,
            event.repeatCount,
            event.metaState,
            event.deviceId,
            event.scanCode,
            event.flags,
            event.source,
        )
    }
}
