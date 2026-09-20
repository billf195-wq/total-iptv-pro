package com.totaliptv.pro.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.ZoneId
import java.time.ZoneOffset

class OsTimeZoneTest {

    @Test
    fun windowsCentralKeyIsAmericaChicagoNotHardcodedDisplayOverride() {
        assertEquals(ZoneId.of("America/Chicago"), OsTimeZone.fromWindowsKey("Central Standard Time"))
        assertEquals(ZoneId.of("America/Chicago"), OsTimeZone.fromWindowsKey("Central Daylight Time"))
        assertEquals(ZoneId.of("America/New_York"), OsTimeZone.fromWindowsKey("Eastern Standard Time"))
        assertEquals(ZoneId.of("UTC"), OsTimeZone.fromWindowsKey("UTC"))
        assertNull(OsTimeZone.fromWindowsKey("   "))
    }

    @Test
    fun windowsOsZoneUsesTzutilKeyWhenJreReportsUtc() {
        val chicago = OsTimeZone.windowsOsZone(
            windows = true,
            tzutilOutput = "Central Standard Time"
        )
        assertEquals(ZoneId.of("America/Chicago"), chicago)
        assertNull(OsTimeZone.windowsOsZone(windows = false, tzutilOutput = "Central Standard Time"))
    }

    @Test
    fun currentMatchesSystemDefaultOnLinuxCi() {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (!os.contains("win")) {
            assertEquals(ZoneId.systemDefault(), OsTimeZone.current())
        }
    }

    @Test
    fun utcLikeDetectsGmtAndOffsetZero() {
        assertTrue(OsTimeZone.isUtcLike(ZoneOffset.UTC))
        assertTrue(OsTimeZone.isUtcLike(ZoneId.of("UTC")))
        assertFalse(OsTimeZone.isUtcLike(ZoneId.of("America/Chicago")))
    }
}
