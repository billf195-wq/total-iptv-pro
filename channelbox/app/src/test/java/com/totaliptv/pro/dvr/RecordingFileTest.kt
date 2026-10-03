package com.totaliptv.pro.dvr

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingFileTest {
    @Test
    fun trimDropsThePartialLastPacket() {
        val file = File.createTempFile("rec", ".ts")
        val partial = RecordingFile.TS_PACKET * 3 + 40
        file.writeBytes(ByteArray(partial) { 0x47 })
        val kept = RecordingFile.trimToWholePackets(file)
        assertEquals((RecordingFile.TS_PACKET * 3).toLong(), kept)
        assertEquals(kept, file.length())
        assertTrue(file.length() % RecordingFile.TS_PACKET == 0L)
        file.delete()
    }

    @Test
    fun wholeFileIsLeftAlone() {
        val file = File.createTempFile("rec", ".ts")
        val whole = RecordingFile.TS_PACKET * 2
        file.writeBytes(ByteArray(whole))
        assertEquals(whole.toLong(), RecordingFile.trimToWholePackets(file))
        assertEquals(whole.toLong(), file.length())
        file.delete()
    }
}
