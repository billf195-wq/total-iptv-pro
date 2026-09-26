package com.totaliptv.pro.dvr

import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingReconcileTest {
    @Test
    fun staleRecordingBecomesStoppedOrFailed() {
        val big = RecordingEntry(
            id = "a",
            channelName = "CNN",
            title = "News",
            startMs = 1L,
            filePath = "/tmp/a.ts",
            status = RecordingStatus.RECORDING.name
        )
        val empty = big.copy(id = "b", filePath = "/tmp/b.ts")
        val live = big.copy(id = "c")
        val fixed = RecordingReconcile.interrupted(
            listOf(big, empty, live),
            activeId = "c"
        ) { path -> if (path.endsWith("a.ts")) 1_600_000_000L else 0L }
        assertEquals(2, fixed.size)
        assertEquals(RecordingStatus.STOPPED.name, fixed.first { it.id == "a" }.status)
        assertEquals("Interrupted", fixed.first { it.id == "a" }.errorMessage)
        assertEquals(RecordingStatus.FAILED.name, fixed.first { it.id == "b" }.status)
        assertEquals("No video saved", fixed.first { it.id == "b" }.errorMessage)
    }
}
