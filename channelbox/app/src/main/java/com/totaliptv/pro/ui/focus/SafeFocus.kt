package com.totaliptv.pro.ui.focus

import android.app.Activity
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.focus.FocusRequester
import com.totaliptv.pro.BuildConfig
import com.totaliptv.pro.diagnostics.CrashLog
import com.totaliptv.pro.diagnostics.DebugLog

/**
 * Where the user is, so a swallowed focus crash can name the screen later.
 * Updated from composition. Not a credential or a provider host.
 */
object FocusTrace {
    @Volatile var screen: String = "unknown"
    @Volatile var focused: String = ""
}

/**
 * [FocusRequester.requestFocus] throws when the requester is not attached.
 * Directional [androidx.compose.ui.focus.focusProperties] targets do the same
 * inside focus search, which the caller cannot catch. Prefer this helper, and
 * do not point focusProperties at a requester that can leave composition.
 */
object SafeFocus {
    const val UNINITIALIZED = "FocusRequester is not initialized"

    fun isUninitialized(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message
            if (current is IllegalStateException && message != null && message.contains(UNINITIALIZED)) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /** @return true when focus moved. False when the target is not on screen. */
    fun request(target: FocusRequester?): Boolean {
        if (target == null) return false
        return try {
            target.requestFocus()
            true
        } catch (error: RuntimeException) {
            if (!isUninitialized(error)) throw error
            false
        }
    }
}

/**
 * Last line of defense. Compose focus search throws from [Activity.dispatchKeyEvent]
 * when a D-pad key resolves to a detached [FocusRequester]. Swallow that one error.
 */
object FocusCrashGuard {
    fun guard(activity: Activity, event: KeyEvent, dispatch: () -> Boolean): Boolean {
        return try {
            dispatch()
        } catch (error: RuntimeException) {
            if (!SafeFocus.isUninitialized(error)) throw error
            record(activity, event, error)
            true
        }
    }

    fun describe(keyCode: Int): String {
        val keyName = runCatching { KeyEvent.keyCodeToString(keyCode) }.getOrDefault("key-$keyCode")
        return "non-fatal focus search screen=${FocusTrace.screen} focused=${FocusTrace.focused} " +
            "keyCode=$keyCode key=$keyName"
    }

    private fun record(activity: Activity, event: KeyEvent, error: Throwable) {
        val summary = describe(event.keyCode)
        CrashLog.recordNonFatal(activity, summary, error)
        if (BuildConfig.DEBUG) {
            Log.w("TotalIPTV.Focus", summary, error)
        }
        DebugLog.append(activity, "Focus", summary, error)
    }
}
