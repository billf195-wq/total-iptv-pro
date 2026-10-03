package com.totaliptv.pro.ui.player

import androidx.media3.common.C
import com.totaliptv.pro.data.model.WatchProgress

/**
 * Decides whether a VOD/series open should seek.
 *
 * Duration is often still [C.TIME_UNSET] on the first READY callback for a
 * progressive movie. Seeking then runs inside the player listener and can
 * throw; callers must wait and try again instead of seeking blind.
 */
object PlaybackResume {
    const val NEAR_END_MS = 30_000L
    const val COMPLETE_FRACTION = 0.92f

    sealed class Decision {
        /** Player has not reported a duration yet. Do not seek and do not give up. */
        data object WaitForDuration : Decision()

        /** Start at 0. Saved progress is missing, finished, or not worth resuming. */
        data object Skip : Decision()

        /** User chose Restart. Caller clears saved progress. */
        data object StartOver : Decision()

        data class Seek(val positionMs: Long) : Decision()
    }

    fun decide(startOver: Boolean, saved: WatchProgress?, durationMs: Long): Decision {
        if (startOver) return Decision.StartOver
        if (saved == null || !saved.shouldResume()) return Decision.Skip
        if (durationMs == C.TIME_UNSET || durationMs <= 0L) return Decision.WaitForDuration
        val target = saved.positionMs
        if (target <= 0L || target >= durationMs) return Decision.Skip
        if (target >= durationMs - NEAR_END_MS) return Decision.Skip
        if (target.toFloat() / durationMs.toFloat() >= COMPLETE_FRACTION) return Decision.Skip
        return Decision.Seek(target)
    }
}
