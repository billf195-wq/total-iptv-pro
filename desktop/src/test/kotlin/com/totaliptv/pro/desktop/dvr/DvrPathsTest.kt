package com.totaliptv.pro.desktop.dvr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Path

class DvrPathsTest {

    @Test
    fun windowsDefaultIsLocalAppDataNotForcedMediaDrive() {
        val dir = DvrPaths.defaultRecordingsDir(
            windows = true,
            userHome = "C:\\Users\\Bill",
            localAppData = "C:\\Users\\Bill\\AppData\\Local"
        )
        assertEquals(Path.of("C:\\Users\\Bill\\AppData\\Local", "TotalIptvPro", "Recordings"), dir)
        assertFalse(dir.toString().contains("D:\\Media"), "must not force a shared D:\\Media path")
        assertFalse(dir.toString().contains("Bigboybill", ignoreCase = true))
    }

    @Test
    fun windowsFallsBackToUserLocalWhenEnvMissing() {
        val dir = DvrPaths.defaultRecordingsDir(
            windows = true,
            userHome = "C:\\Users\\Bill",
            localAppData = null
        )
        assertEquals(Path.of("C:\\Users\\Bill", "AppData", "Local", "TotalIptvPro", "Recordings"), dir)
    }

    @Test
    fun linuxDefaultIsVideosTotalIptvPro() {
        val dir = DvrPaths.defaultRecordingsDir(
            windows = false,
            userHome = "/home/bill",
            videosDir = "/home/bill/Videos"
        )
        assertEquals(Path.of("/home/bill/Videos", "TotalIptvPro", "Recordings"), dir)
        assertFalse(dir.toString().startsWith("/mnt/"), "must stay on this machine")
    }

    @Test
    fun linuxFallbackIsLocalShare() {
        assertEquals(
            Path.of("/home/bill", ".local", "share", "total-iptv-pro", "recordings"),
            DvrPaths.linuxFallbackDir("/home/bill")
        )
    }

    @Test
    fun customOverrideWinsOnThatDeviceOnly() {
        val dir = DvrPaths.resolveRecordingsDir(
            override = "/home/bill/Recordings",
            windows = false,
            userHome = "/home/bill"
        )
        assertEquals(Path.of("/home/bill/Recordings"), dir)
    }

    @Test
    fun blankOverrideUsesDefault() {
        val dir = DvrPaths.resolveRecordingsDir(
            override = "  ",
            windows = false,
            userHome = "/home/gtr",
            videosDir = "/home/gtr/Videos"
        )
        assertEquals(Path.of("/home/gtr/Videos", "TotalIptvPro", "Recordings"), dir)
    }

    @Test
    fun sanitizeStripsPathAndReservedChars() {
        val name = DvrPaths.sanitizeFileName("CNN/Live:*News?<>|")
        assertFalse(name.contains("/"))
        assertFalse(name.contains(":"))
        assertFalse(name.contains("*"))
        assertTrue(name.contains("CNN"))
        assertTrue(name.contains("News"))
    }

    @Test
    fun recordingFileNameIncludesChannelTitleAndStamp() {
        val name = DvrPaths.recordingFileName("USA Movies", "The Godfather", 1_700_000_000_000L)
        assertTrue(name.startsWith("USA Movies_The Godfather_"))
        assertTrue(name.endsWith(".ts"))
        assertFalse(name.contains("/"))
    }

    @Test
    fun recordingFileNameUsesMovieExtension() {
        val name = DvrPaths.recordingFileName("Movies", "Heat", 1_700_000_000_000L, "mp4")
        assertTrue(name.endsWith(".mp4"))
        assertTrue(name.contains("Heat"))
    }
}
