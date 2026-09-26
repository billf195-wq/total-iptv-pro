package com.totaliptv.pro.ui.components

/**
 * Movies and Series search must keep the text field while results update.
 * Live search never called requestFocus on the first row; the poster grid did.
 */
object SearchTyping {
    const val DEBOUNCE_MS = 350L
    const val STRAY_OK_WINDOW_MS = 400L

    /** Results and chips cannot take focus while the field is editing. */
    fun resultsMayTakeFocus(editing: Boolean): Boolean = !editing

    /**
     * OK on the idle box opens the IME. LatinIME's first key is often 'q',
     * so that same OK inserts a stray character. Drop only that one insert.
     */
    fun isStrayOkCharacter(previous: String, next: String, openedAtMs: Long, nowMs: Long): Boolean {
        if (openedAtMs <= 0L || nowMs - openedAtMs > STRAY_OK_WINDOW_MS) return false
        return next == previous + "q"
    }
}
