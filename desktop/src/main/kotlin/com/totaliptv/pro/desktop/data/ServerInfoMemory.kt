package com.totaliptv.pro.desktop.data

import java.time.ZoneId

/**
 * Remembers Xtream `server_info` from the login probe.
 *
 * A zero offset or a missing timezone is still a finished probe. Later guide
 * lookups must not download player_api again just because the offset was 0.
 * Guide times themselves stay on [EpgTime] / [GuideTime] (1.2.10 / 1.2.11).
 */
internal class ServerInfoMemory {
    @Volatile
    var zone: ZoneId? = null
        private set

    @Volatile
    var cached: Boolean = false
        private set

    fun remember(parsed: ZoneId?) {
        if (cached) return
        zone = parsed
        cached = true
    }
}
