package com.totaliptv.pro.dvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class DvrStoreTest {
    @Test
    fun persistRecordingAndSchedule() {
        val store = DvrStore(createTempDirectory("android-dvr-"))
        store.upsert(
            RecordingEntry(
                id = "r1",
                channelName = "CNN",
                title = "News",
                startMs = 100L,
                durationMs = 50L,
                filePath = "/tmp/a.ts",
                status = RecordingStatus.COMPLETED.name
            )
        )
        store.addSchedule(
            ScheduledRecording(
                id = "s1",
                channelName = "HBO",
                title = "Film",
                streamUrl = "http://x/1.m3u8",
                startMs = 200L,
                endMs = 300L
            )
        )
        assertEquals(1, store.recordings().size)
        assertEquals("CNN", store.recordings()[0].channelName)
        assertEquals("/tmp/a.ts", store.recordings()[0].filePath)
        assertEquals(1, store.schedules().size)
        store.removeSchedule("s1")
        store.removeRecording("r1")
        assertTrue(store.recordings().isEmpty())
        assertTrue(store.schedules().isEmpty())
    }
}
