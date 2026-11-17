package com.totaliptv.pro.ui.player

/** Player sleep timer choices and the warning window before playback stops. */
object SleepTimer {
    /** Warn this long before the timer stops playback. */
    const val WARN_MS = 60_000L

    sealed class Choice(val label: String) {
        object Off : Choice("Off")
        class Minutes(val minutes: Int) : Choice("$minutes minutes")
        class EndOfTitle(label: String) : Choice(label)
    }

    val MINUTE_OPTIONS = listOf(15, 30, 60, 90)

    /** Off, 15, 30, 60, 90 minutes, plus End of this episode / movie for on-demand titles. */
    fun choices(onDemand: Boolean, seriesEpisode: Boolean): List<Choice> {
        val out = mutableListOf<Choice>(Choice.Off)
        MINUTE_OPTIONS.forEach { out.add(Choice.Minutes(it)) }
        if (onDemand) {
            out.add(Choice.EndOfTitle(if (seriesEpisode) "End of this episode" else "End of this movie"))
        }
        return out
    }

    /** Short label for the player button. */
    fun buttonLabel(remainingMs: Long?, atEnd: Boolean): String = when {
        atEnd -> "Sleep: end"
        remainingMs == null -> "Sleep"
        else -> {
            val mins = ((remainingMs.coerceAtLeast(0L) + 59_999L) / 60_000L).coerceAtLeast(1L)
            "Sleep ${mins}m"
        }
    }

    fun shouldWarn(remainingMs: Long): Boolean = remainingMs in 1..WARN_MS

    fun warningMessage(remainingMs: Long): String {
        val secs = ((remainingMs.coerceAtLeast(0L) + 999L) / 1000L)
        return "Playback will stop in ${secs}s."
    }
}
