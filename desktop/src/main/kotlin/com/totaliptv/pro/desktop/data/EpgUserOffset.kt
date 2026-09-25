package com.totaliptv.pro.desktop.data

/**
 * Optional hours the user adds in Settings on top of the existing guide clock.
 * Zero means "Auto": keep [EpgTime] / OS timezone and do not shift anything.
 */
object EpgUserOffset {
    val CHOICES: List<Pair<Int, String>> = listOf(
        0 to "Auto",
        -5 to "-5 hrs",
        -4 to "-4 hrs",
        -1 to "-1 hr",
        1 to "+1 hr",
        2 to "+2 hrs"
    )

    fun apply(programs: List<EpgProgram>, hours: Int): List<EpgProgram> {
        if (hours == 0 || programs.isEmpty()) return programs
        val delta = hours * 3_600_000L
        return programs.map { it.copy(startMs = it.startMs + delta, endMs = it.endMs + delta) }
    }

    fun apply(epg: ChannelEpg, hours: Int): ChannelEpg {
        if (hours == 0 || epg.programs.isEmpty()) return epg
        return epg.copy(programs = apply(epg.programs, hours))
    }
}
