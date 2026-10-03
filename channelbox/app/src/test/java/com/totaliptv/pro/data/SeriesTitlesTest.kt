package com.totaliptv.pro.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SeriesTitlesTest {
    @Test
    fun doesNotRepeatSeriesNameOrEpisodeCode() {
        val already = SeriesTitles.label("Dark", 1, 1, "Dark S01E01 Secrets")
        assertEquals("Dark S01E01 Secrets", already)
        assertFalse(already.contains("Dark — Dark"))
        val plain = SeriesTitles.label("Dark", 1, 2, "Lies")
        assertEquals("Dark — S1E2 Lies", plain)
        assertFalse(plain.contains("S1E2 S1E2"))
    }
}
