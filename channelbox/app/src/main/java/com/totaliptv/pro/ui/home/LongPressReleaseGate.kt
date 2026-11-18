package com.totaliptv.pro.ui.home

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A menu opened by a long press must not react to the rest of that same press.
 * Holding OK opens the menu while the button is still down; the repeats and the
 * release that follow belong to the old press, so they are swallowed. Only a fresh
 * press made after the menu appeared can activate an item.
 */
class LongPressReleaseGate {
    var armed: Boolean = false
        private set

    /**
     * Feed every OK/Enter key event. Returns true when the event must be consumed
     * (it belongs to the press that opened the menu).
     */
    fun onSelectKey(isDown: Boolean, repeatCount: Int): Boolean {
        if (armed) return false
        if (isDown && repeatCount == 0) {
            armed = true
            return false
        }
        return true
    }

    /** A new finger or mouse press inside the menu arms it. */
    fun onPointerDown() {
        armed = true
    }

    /** Clicks (touch or key) only count once the gate is armed. */
    fun allowsClick(): Boolean = armed
}

private fun Key.isSelectKey(): Boolean =
    this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter

/** Swallows the OK/Enter repeats and release left over from the long press. */
fun Modifier.longPressReleaseGate(gate: LongPressReleaseGate): Modifier =
    this
        .onPreviewKeyEvent { ev ->
            if (!ev.key.isSelectKey()) return@onPreviewKeyEvent false
            when (ev.type) {
                KeyEventType.KeyDown -> gate.onSelectKey(true, ev.nativeKeyEvent.repeatCount)
                KeyEventType.KeyUp -> gate.onSelectKey(false, 0)
                else -> false
            }
        }
        .pointerInput(gate) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (e.type == PointerEventType.Press) gate.onPointerDown()
                }
            }
        }
