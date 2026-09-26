package com.totaliptv.pro.dvr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DvrFinishTest {
    @Test
    fun diskFullIsFailedEvenWhenBytesWereWritten() {
        assertEquals(
            RecordingStatus.FAILED,
            DvrRecorder.recordingFinishStatus(stopped = false, bytesWritten = 4096L, failed = true)
        )
        assertEquals(
            "Not enough storage to keep recording",
            DvrRecorder.recordingErrorMessage(
                RecordingStatus.FAILED,
                storageFailure = true,
                failed = true
            )
        )
    }

    @Test
    fun userStopWinsAndACleanFileCompletes() {
        assertEquals(
            RecordingStatus.STOPPED,
            DvrRecorder.recordingFinishStatus(stopped = true, bytesWritten = 100L, failed = true)
        )
        assertNull(
            DvrRecorder.recordingErrorMessage(
                RecordingStatus.STOPPED,
                storageFailure = false,
                failed = true
            )
        )
        assertEquals(
            RecordingStatus.COMPLETED,
            DvrRecorder.recordingFinishStatus(stopped = false, bytesWritten = 100L, failed = false)
        )
        assertEquals(
            RecordingStatus.FAILED,
            DvrRecorder.recordingFinishStatus(stopped = false, bytesWritten = 0L, failed = false)
        )
        assertEquals(
            "No data written",
            DvrRecorder.recordingErrorMessage(
                RecordingStatus.FAILED,
                storageFailure = false,
                failed = false
            )
        )
    }
}
