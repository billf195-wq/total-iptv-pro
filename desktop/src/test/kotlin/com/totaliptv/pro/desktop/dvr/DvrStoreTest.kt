package com.totaliptv.pro.desktop.dvr

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DvrStoreTest {

    private fun store(): DvrStore {
        val dir = createTempDirectory("tip-dvr-")
        return DvrStore(dir)
    }

    @Test
    fun emptyWhenMissing() {
        val s = store()
        assertTrue(s.recordings().isEmpty())
        assertTrue(s.schedules().isEmpty())
    }

    @Test
    fun upsertAndReloadMetadata() {
        val s = store()
        val entry = RecordingEntry(
            id = "r1",
            channelName = "CNN",
            title = "News Hour",
            startMs = 1_700_000_000_000L,
            durationMs = 3_600_000L,
            filePath = "/tmp/cnn.ts",
            streamUrl = "http://host/live/u/p/1.m3u8",
            status = RecordingStatus.COMPLETED.name
        )
        s.upsert(entry)
        val loaded = s.recordings()
        assertEquals(1, loaded.size)
        assertEquals("CNN", loaded[0].channelName)
        assertEquals("News Hour", loaded[0].title)
        assertEquals("/tmp/cnn.ts", loaded[0].filePath)
        assertEquals(3_600_000L, loaded[0].durationMs)
        assertTrue(loaded[0].playable())
    }

    @Test
    fun replaceSameIdKeepsSingleRow() {
        val s = store()
        s.upsert(RecordingEntry(id = "r1", channelName = "A", title = "T", startMs = 1L, status = "RECORDING"))
        s.upsert(RecordingEntry(id = "r1", channelName = "A", title = "T", startMs = 1L, status = "COMPLETED", filePath = "a.ts"))
        assertEquals(1, s.recordings().size)
        assertEquals(RecordingStatus.COMPLETED, s.recordings()[0].statusEnum())
    }

    @Test
    fun scheduleRoundTripAndRemove() {
        val s = store()
        val sched = ScheduledRecording(
            id = "s1",
            channelName = "HBO",
            title = "Movie",
            streamUrl = "http://host/live/u/p/9.m3u8",
            startMs = 2L,
            endMs = 3L
        )
        s.addSchedule(sched)
        assertEquals(1, s.schedules().size)
        s.removeSchedule("s1")
        assertTrue(s.schedules().isEmpty())
    }

    @Test
    fun corruptJsonYieldsEmpty() {
        val dir = createTempDirectory("tip-dvr-bad-")
        dir.resolve("recordings.json").toFile().writeText("{not-json")
        val s = DvrStore(dir)
        assertTrue(s.load().recordings.isEmpty())
    }
}
