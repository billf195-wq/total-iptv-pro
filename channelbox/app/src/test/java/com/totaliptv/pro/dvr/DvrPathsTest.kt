package com.totaliptv.pro.dvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DvrPathsTest {
    @Test
    fun androidDefaultUsesAppMoviesOnThisDevice() {
        val dir = DvrPaths.defaultRecordingsDir(
            filesDir = "/data/user/0/com.totaliptv.pro/files",
            externalMoviesDir = "/storage/emulated/0/Android/data/com.totaliptv.pro/files/Movies"
        )
        assertEquals(
            "/storage/emulated/0/Android/data/com.totaliptv.pro/files/Movies/TotalIptvPro/Recordings",
            dir
        )
        assertFalse(dir.contains("Bigboybill", ignoreCase = true))
        assertFalse(dir.contains("/mnt/nas"))
    }

    @Test
    fun fallsBackToAppPrivateWhenNoExternalMovies() {
        val dir = DvrPaths.defaultRecordingsDir(filesDir = "/data/data/com.totaliptv.pro/files")
        assertEquals("/data/data/com.totaliptv.pro/files/recordings", dir)
    }

    @Test
    fun overrideStaysOnThatDevice() {
        val dir = DvrPaths.resolveRecordingsDir(
            override = "/storage/emulated/0/Movies/MyDvr",
            filesDir = "/data/data/x/files"
        )
        assertEquals("/storage/emulated/0/Movies/MyDvr", dir)
    }

    @Test
    fun sanitizeAndFileName() {
        val name = DvrPaths.recordingFileName("CNN/Live", "News:Hour?", 1_700_000_000_000L)
        assertTrue(name.contains("CNN"))
        assertTrue(name.endsWith(".ts"))
        assertFalse(name.contains("/"))
        assertFalse(name.contains(":"))
    }
}
