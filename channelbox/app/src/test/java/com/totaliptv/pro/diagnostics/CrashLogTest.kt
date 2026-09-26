package com.totaliptv.pro.diagnostics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrashLogTest {
    @Test
    fun writesStackTraceThatSettingsCanRead() {
        val dir = File.createTempFile("crash", "dir")
        dir.delete()
        dir.mkdirs()
        val boom = IllegalStateException("seek during listener")
        CrashLog.writeTo(dir, "main", boom)
        val text = CrashLog.readFrom(dir)
        assertTrue(text.contains("Thread: main"))
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("seek during listener"))
        assertTrue(text.contains("CrashLogTest"))
        val debug = DebugLog.readFrom(dir)
        assertTrue(debug.contains("uncaught on main"))
        assertTrue(debug.contains("seek during listener"))
    }

    @Test
    fun debugLogKeepsPlaybackFailures() {
        val dir = File.createTempFile("debug", "dir")
        dir.delete()
        dir.mkdirs()
        DebugLog.appendTo(dir, "TotalIPTV.Player", "Audio selection failed", IndexOutOfBoundsException("track 4"))
        val text = DebugLog.readFrom(dir)
        assertTrue(text.contains("TotalIPTV.Player: Audio selection failed"))
        assertTrue(text.contains("IndexOutOfBoundsException"))
        assertTrue(text.contains("track 4"))
    }

    @Test
    fun formatIncludesThreadAndTime() {
        val text = CrashLog.format("exo-playback", IllegalArgumentException("bad mime"), 0L)
        assertTrue(text.startsWith("Time: "))
        assertEquals(true, text.contains("Thread: exo-playback"))
        assertTrue(text.contains("bad mime"))
    }
}
