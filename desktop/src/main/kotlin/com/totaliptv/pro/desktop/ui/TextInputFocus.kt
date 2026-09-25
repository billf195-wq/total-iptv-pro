package com.totaliptv.pro.desktop.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks whether a text field has focus so window shortcuts (Space = stop)
 * do not fire while the user is typing in search or settings.
 */
object TextInputFocus {
    private val depth = AtomicInteger(0)

    fun enter() {
        depth.incrementAndGet()
    }

    fun exit() {
        depth.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun isActive(): Boolean = depth.get() > 0

    internal fun resetForTests() {
        depth.set(0)
    }
}

fun Modifier.trackTextInputFocus(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            if (focused) {
                focused = false
                TextInputFocus.exit()
            }
        }
    }
    onFocusChanged { state ->
        val now = state.isFocused
        if (now && !focused) TextInputFocus.enter()
        if (!now && focused) TextInputFocus.exit()
        focused = now
    }
}
