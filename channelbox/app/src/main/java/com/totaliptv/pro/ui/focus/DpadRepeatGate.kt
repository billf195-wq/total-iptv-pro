package com.totaliptv.pro.ui.focus

import android.view.KeyEvent

/**
 * Keeps held D-pad keys from piling up on the main thread (Shield ANRs 2026-10-03).
 *
 * Each D-pad press runs a Compose focus search. Near the end of a long list, or when the
 * next card is not composed yet, that search lays out items beyond the screen one at a time
 * and can take hundreds of ms. Holding a key sends ~20 repeats/s, so the backlog grows until
 * Android reports "not responding" and the app is closed.
 *
 * Rules (first press and key-up always pass):
 * - drop a repeat that is already stale (main thread is behind),
 * - drop a repeat that arrives sooner than [MIN_REPEAT_GAP_MS] after the last one we handled,
 * - after a slow repeat ([SLOW_DISPATCH_MS]), ignore further repeats of that key until it is
 *   released; the user presses again to keep moving.
 */
class DpadRepeatGate(
    private val minRepeatGapMs: Long = MIN_REPEAT_GAP_MS,
    private val staleMs: Long = STALE_MS,
    private val slowDispatchMs: Long = SLOW_DISPATCH_MS
) {
    private var lastHandledAt = 0L
    private var blockedKey = -1

    /** True when this event should be dropped (consumed without dispatch). */
    fun shouldDrop(keyCode: Int, action: Int, repeatCount: Int, eventTime: Long, now: Long): Boolean {
        if (!isDpad(keyCode)) return false
        if (action == KeyEvent.ACTION_UP) {
            if (blockedKey == keyCode) blockedKey = -1
            return false
        }
        if (action != KeyEvent.ACTION_DOWN) return false
        if (repeatCount == 0) {
            blockedKey = -1
            return false
        }
        if (blockedKey == keyCode) return true
        if (now - eventTime > staleMs) return true
        if (now - lastHandledAt < minRepeatGapMs) return true
        return false
    }

    /** Call after a D-pad key-down was dispatched; [tookMs] is how long dispatch ran. */
    fun onDispatched(keyCode: Int, repeatCount: Int, finishedAt: Long, tookMs: Long) {
        if (!isDpad(keyCode)) return
        lastHandledAt = finishedAt
        if (repeatCount > 0 && tookMs >= slowDispatchMs) blockedKey = keyCode
    }

    fun shouldDrop(event: KeyEvent, now: Long): Boolean =
        shouldDrop(event.keyCode, event.action, event.repeatCount, event.eventTime, now)

    companion object {
        const val MIN_REPEAT_GAP_MS = 70L
        const val STALE_MS = 160L
        const val SLOW_DISPATCH_MS = 250L

        fun isDpad(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
    }
}