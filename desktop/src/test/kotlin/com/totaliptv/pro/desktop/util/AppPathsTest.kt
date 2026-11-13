package com.totaliptv.pro.desktop.util

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class AppPathsTest {

    @Test
    fun macUsesApplicationSupport() {
        val dir = configDirFor(
            isWindows = false,
            isMac = true,
            home = "/Users/bill",
            appData = null
        )
        assertEquals(Path.of("/Users/bill", "Library", "Application Support", "total-iptv-pro"), dir)
    }

    @Test
    fun windowsUsesAppDataThenRoamingFallback() {
        val fromEnv = configDirFor(true, false, "/unused", "D:/AppData")
        assertEquals(Path.of("D:/AppData", "total-iptv-pro"), fromEnv)
        val fallback = configDirFor(true, false, "/home/bill", null)
        assertEquals(Path.of("/home/bill", "AppData", "Roaming", "total-iptv-pro"), fallback)
    }

    @Test
    fun linuxUsesDotConfig() {
        val dir = configDirFor(false, false, "/home/bill", null)
        assertEquals(Path.of("/home/bill", ".config", "total-iptv-pro"), dir)
    }

    @Test
    fun linuxHonorsXdgConfigHome() {
        val dir = configDirFor(
            isWindows = false,
            isMac = false,
            home = "/home/bill",
            appData = null,
            xdgConfigHome = "/custom/config"
        )
        assertEquals(Path.of("/custom/config", "total-iptv-pro"), dir)
        val fallback = configDirFor(false, false, "/home/bill", null, xdgConfigHome = "  ")
        assertEquals(Path.of("/home/bill", ".config", "total-iptv-pro"), fallback)
    }
}
