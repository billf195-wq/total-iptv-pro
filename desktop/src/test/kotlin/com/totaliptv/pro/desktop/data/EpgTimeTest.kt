package com.totaliptv.pro.desktop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.Instant
import java.time.ZoneId

class EpgTimeTest {

    private val chicago: ZoneId = ZoneId.of("America/Chicago")
    private val utc: ZoneId = ZoneId.of("UTC")
    private val epochSec: Long = Instant.parse("2026-09-20T19:00:00Z").epochSecond
    private val epochMs: Long = epochSec * 1000L

    @Test
    fun unixSecondsAreUtcEpochMsNotLocalWallTime() {
        assertEquals(epochMs, EpgTime.fromUnixOrMillis(epochSec))
        assertEquals(epochMs, EpgTime.fromUnixOrMillis(epochMs))
        assertEquals("2:00 PM", GuideTime.formatTime(EpgTime.fromUnixOrMillis(epochSec), chicago))
        assertEquals("7:00 PM", GuideTime.formatTime(EpgTime.fromUnixOrMillis(epochSec), utc))
    }

    @Test
    fun naiveXtreamTextIsUtcNotComputerLocal() {
        assertEquals(epochMs, EpgTime.fromText("2026-09-20 19:00:00"))
        assertEquals(epochMs, EpgTime.fromText("2026-09-20T19:00:00Z"))
        assertEquals(epochMs, EpgTime.fromText("2026-09-20T14:00:00-05:00"))
        assertEquals("2:00 PM", GuideTime.formatTime(EpgTime.fromText("2026-09-20 19:00:00"), chicago))
    }

    @Test
    fun timestampFieldWinsOverNaiveText() {
        assertEquals(epochMs, EpgTime.fromFields(epochSec.toString(), "1999-01-01 00:00:00"))
        assertEquals(epochMs, EpgTime.fromFields(null, "2026-09-20 19:00:00"))
        assertEquals(0L, EpgTime.fromFields(null, null))
        assertEquals(0L, EpgTime.fromFields("0", ""))
    }

    @Test
    fun xtreamListingParseKeepsUtcEpochAndLocalDisplay() {
        val body = """
            {"epg_listings":[
              {
                "id":"p1",
                "title":"Evening News",
                "start_timestamp":"$epochSec",
                "stop_timestamp":"${epochSec + 3600}",
                "start":"2026-09-20 19:00:00",
                "end":"2026-09-20 20:00:00"
              }
            ]}
        """.trimIndent()
        val programs = XtreamApi().parseEpgListings(body, 341)
        assertEquals(1, programs.size)
        assertEquals(epochMs, programs[0].startMs)
        assertEquals(epochMs + 3_600_000L, programs[0].endMs)
        assertTrue(programs[0].contains(epochMs + 1_000L))
        assertEquals("2:00 PM", GuideTime.formatTime(programs[0].startMs, chicago))
        val obj = Json.parseToJsonElement("""{"start_timestamp":"$epochSec","start":"ignored"}""") as JsonObject
        assertEquals(epochMs, XtreamApi().epgTimeMs(obj, "start_timestamp", "start"))
    }
}
