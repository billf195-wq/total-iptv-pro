package com.totaliptv.pro.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Instant
import java.time.ZoneId

class GuideTimeTest {

    private val chicago: ZoneId = ZoneId.of("America/Chicago")
    private val london: ZoneId = ZoneId.of("Europe/London")
    private val utc: ZoneId = ZoneId.of("UTC")

    /** 2026-09-20 19:00:00 UTC = 2:00 PM CDT / 8:00 PM BST. */
    private val epochMs: Long = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()

    @Test
    fun zoneFollowsOsTimeZoneNotAHardcodedDisplayZone() {
        assertEquals(OsTimeZone.current(), GuideTime.zone())
        val src = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/data/GuideTime.kt").readText()
        assertTrue(src.contains("OsTimeZone.current()"))
        assertFalse(src.contains("America/Chicago"))
        val guide = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/GuideScreen.kt").readText()
        assertTrue(guide.contains("GuideTime.formatClock"))
        assertTrue(guide.contains("GuideTime.formatHourTick"))
        assertTrue(guide.contains("GuideTime.formatRange") || guide.contains("GuideTime.formatTime"))
        assertTrue(guide.contains("GuideTime.nowMs"))
        assertFalse(guide.contains("America/Chicago"))
        assertFalse(guide.contains("LocalDateTime.now()"))
    }

    @Test
    fun epochMsIsUtcInstantFormattedInLocalZone() {
        assertEquals("2:00 PM", GuideTime.formatTime(epochMs, chicago))
        assertEquals("7:00 PM", GuideTime.formatTime(epochMs, utc))
        assertEquals("8:00 PM", GuideTime.formatTime(epochMs, london))
        assertEquals("2:00 PM – 3:00 PM", GuideTime.formatRange(epochMs, epochMs + 3_600_000L, chicago))
        assertTrue(GuideTime.formatClock(epochMs, chicago).contains("2:00 PM"))
        assertTrue(GuideTime.formatHourTick(epochMs, chicago).contains("2"))
        assertTrue(GuideTime.formatHourTick(epochMs, utc).contains("7"))
    }

    @Test
    fun hourTicksSitOnLocalHoursWhenOsZoneIsChicagoOrUtc() {
        val windowStart = Instant.parse("2026-09-20T22:37:00Z").toEpochMilli() // 5:37 PM CDT
        val windowEnd = windowStart + 3 * 3_600_000L
        val chiTicks = GuideTime.hourTicks(windowStart, windowEnd, chicago)
        assertTrue(chiTicks.isNotEmpty())
        chiTicks.forEach { t ->
            val z = Instant.ofEpochMilli(t).atZone(chicago)
            assertEquals(0, z.minute)
            assertEquals(0, z.second)
        }
        assertEquals("6 PM", GuideTime.formatHourTick(chiTicks.first(), chicago))
        val utcTicks = GuideTime.hourTicks(windowStart, windowEnd, utc)
        assertEquals("11 PM", GuideTime.formatHourTick(utcTicks.first(), utc))
        assertTrue(chiTicks.first() != utcTicks.first() || GuideTime.formatHourTick(chiTicks.first(), chicago) != GuideTime.formatHourTick(utcTicks.first(), utc))
    }
}
