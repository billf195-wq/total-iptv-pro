package com.totaliptv.pro.desktop.data

import com.totaliptv.pro.desktop.util.AppPaths
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * OS timezone for the TV Guide. [ZoneId.systemDefault] is correct on Linux but
 * Windows jpackage / jlink runtimes often report UTC even when the PC is set
 * to Central. Read the Windows Time Zone key (`tzutil /g`) and map it to IANA.
 */
object OsTimeZone {
    /**
     * Windows registry / tzutil key → IANA. Not a display override: if the OS
     * is Eastern this returns New York, not Chicago.
     */
    internal val WINDOWS_TO_IANA: Map<String, String> = mapOf(
        "Central Standard Time" to "America/Chicago",
        "Central Daylight Time" to "America/Chicago",
        "US Central Standard Time" to "America/Chicago",
        "Canada Central Standard Time" to "America/Winnipeg",
        "Eastern Standard Time" to "America/New_York",
        "Eastern Daylight Time" to "America/New_York",
        "US Eastern Standard Time" to "America/Indianapolis",
        "Mountain Standard Time" to "America/Denver",
        "US Mountain Standard Time" to "America/Phoenix",
        "Pacific Standard Time" to "America/Los_Angeles",
        "Alaskan Standard Time" to "America/Anchorage",
        "Hawaiian Standard Time" to "Pacific/Honolulu",
        "Atlantic Standard Time" to "America/Halifax",
        "GMT Standard Time" to "Europe/London",
        "UTC" to "UTC",
        "Greenwich Standard Time" to "Etc/GMT",
        "Romance Standard Time" to "Europe/Paris",
        "W. Europe Standard Time" to "Europe/Berlin",
        "GTB Standard Time" to "Europe/Bucharest",
        "Russian Standard Time" to "Europe/Moscow"
    )

    fun current(): ZoneId = windowsOsZone() ?: ZoneId.systemDefault()

    internal fun fromWindowsKey(key: String): ZoneId? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return null
        WINDOWS_TO_IANA[trimmed]?.let { return zoneOrNull(it) }
        return zoneOrNull(trimmed)
    }

    internal fun zoneOrNull(id: String): ZoneId? = try {
        val z = ZoneId.of(id.trim())
        if (z.id.isBlank()) null else z
    } catch (_: Exception) {
        null
    }

    internal fun windowsOsZone(
        windows: Boolean = AppPaths.isWindows,
        tzutilOutput: String? = if (windows) readTzutil() else null
    ): ZoneId? {
        if (!windows) return null
        return tzutilOutput?.let { fromWindowsKey(it) }
    }

    private fun readTzutil(): String? = try {
        val proc = ProcessBuilder("tzutil.exe", "/g")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        val code = proc.waitFor()
        if (code == 0 && out.isNotBlank()) out else null
    } catch (_: Exception) {
        null
    }

    fun isUtcLike(zone: ZoneId): Boolean {
        if (zone.id.equals("UTC", true) || zone.id.equals("GMT", true) || zone.id.equals("Z", true)) {
            return true
        }
        return zone.normalized() == ZoneOffset.UTC
    }
}
