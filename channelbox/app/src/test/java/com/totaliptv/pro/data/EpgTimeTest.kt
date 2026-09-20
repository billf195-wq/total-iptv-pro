package com.totaliptv.pro.data

import com.totaliptv.pro.data.xtream.XtreamApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class EpgTimeTest {

    private val chicago: ZoneId = ZoneId.of("America/Chicago")
    private val utc: ZoneId = ZoneId.of("UTC")

    /** 2:00 PM CDT = 19:00 UTC on 2026-09-20. */
    private val twoPmChicago: Long = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
    private val twoPmUtc: Long = Instant.parse("2026-09-20T14:00:00Z").toEpochMilli()
    private val epochSec: Long = twoPmChicago / 1000L

    @Test
    fun unixSecondsAreUtcEpochMs() {
        assertEquals(twoPmChicago, EpgTime.fromUnixOrMillis(epochSec))
    }

    @Test
    fun naiveTextUsesOsOrProviderZoneNotForcedUtc() {
        assertEquals(twoPmChicago, EpgTime.fromNaiveInZone("2026-09-20 14:00:00", chicago))
        assertEquals(twoPmUtc, EpgTime.fromNaiveInZone("2026-09-20 14:00:00", ZoneOffset.UTC))
        assertEquals(
            twoPmChicago,
            EpgTime.fromFields(null, "2026-09-20 14:00:00", displayZone = chicago)
        )
    }

    @Test
    fun consistentUnixTimestampWinsOverNaiveLabel() {
        assertEquals(
            twoPmChicago,
            EpgTime.fromFields(epochSec.toString(), "1999-01-01 00:00:00", chicago)
        )
        assertEquals(
            twoPmChicago,
            EpgTime.fromFields(epochSec.toString(), "2026-09-20 19:00:00", chicago, providerZone = utc)
        )
        assertEquals(0L, EpgTime.fromFields(null, null, chicago))
    }

    @Test
    fun localWallStuffedIntoUnixUsesProviderZoneNotUtcEpoch() {
        val fakeUnix = (twoPmUtc / 1000L).toString()
        val fixed = EpgTime.fromFields(
            timestamp = fakeUnix,
            text = "2026-09-20 14:00:00",
            displayZone = chicago,
            providerZone = chicago
        )
        assertEquals(twoPmChicago, fixed)
    }

    @Test
    fun alignToNowPrefersLocalTextWhenTimestampRowMissesNow() {
        val now = twoPmChicago + 60_000L
        val primary = listOf(EpgTime.Instants(twoPmUtc, twoPmUtc + 3_600_000L))
        val local = listOf(EpgTime.Instants(twoPmChicago, twoPmChicago + 3_600_000L))
        val picked = EpgTime.alignToNow(primary, local, now)
        assertEquals(twoPmChicago, picked.single().startMs)
        assertTrue(picked.single().contains(now))
    }

    @Test
    fun inferProviderZoneFromServerClock() {
        assertEquals(
            chicago,
            EpgTime.inferProviderZone("2026-09-20 14:00:00", epochSec)
        )
        val utcLike = EpgTime.inferProviderZone("2026-09-20 19:00:00", epochSec)
        assertTrue(utcLike == utc || utcLike?.id == "Etc/UTC" || EpgTime.isUtcLike(utcLike ?: utc))
    }

    @Test
    fun xtreamListingAlignsMislabeledUnixToChicagoNow() {
        val api = XtreamApi()
        api.providerZone = chicago
        val fakeSec = twoPmUtc / 1000L
        val body = """
            {"epg_listings":[
              {
                "id":"p1",
                "title":"Evening News",
                "start_timestamp":$fakeSec,
                "stop_timestamp":${fakeSec + 3600},
                "start":"2026-09-20 14:00:00",
                "end":"2026-09-20 15:00:00"
              }
            ]}
        """.trimIndent()
        val now = twoPmChicago + 30_000L
        val programs = api.parseEpgListings(body, 341, displayZone = chicago, nowMs = now)
        assertEquals(1, programs.size)
        assertEquals(twoPmChicago, programs[0].startMs)
        assertTrue(programs[0].contains(now))
    }

    @Test
    fun parseServerInfoZoneReadsTimezoneAndTimeNow() {
        val api = XtreamApi()
        val named = api.parseServerInfoZone(
            """{"server_info":{"timezone":"America/Chicago","timestamp_now":$epochSec,"time_now":"2026-09-20 14:00:00"}}"""
        )
        assertEquals(chicago, named)
        val inferred = api.parseServerInfoZone(
            """{"server_info":{"timestamp_now":$epochSec,"time_now":"2026-09-20 14:00:00"}}"""
        )
        assertEquals(chicago, inferred)
    }
}
