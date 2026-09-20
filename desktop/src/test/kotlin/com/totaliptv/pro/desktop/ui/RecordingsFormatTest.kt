package com.totaliptv.pro.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingsFormatTest {
    @Test
    fun durationFormatsHoursMinutesSeconds() {
        assertEquals("—", formatDuration(0))
        assertEquals("1:05", formatDuration(65_000L))
        assertEquals("1:02:03", formatDuration(3_723_000L))
    }
}
