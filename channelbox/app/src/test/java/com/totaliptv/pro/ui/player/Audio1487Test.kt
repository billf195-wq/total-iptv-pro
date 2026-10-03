package com.totaliptv.pro.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Audio1487Test {
    private fun c(en: Boolean, def: Boolean, idx: Int, secondary: Boolean = false) = EnglishAudio.Candidate(
        isEnglish = en, supported = true, hasPlayableChannels = true, isDefault = def,
        secondary = secondary, selected = def, groupIndex = idx, trackIndex = 0
    )

    @Test
    fun englishBeatsNonEnglishDefault() {
        val pick = EnglishAudio.pick(listOf(c(en = false, def = true, idx = 0), c(en = true, def = false, idx = 1)))
        assertEquals(1, pick?.groupIndex)
    }

    @Test
    fun noEnglishKeepsDefault() {
        val pick = EnglishAudio.pick(listOf(c(en = false, def = true, idx = 0), c(en = false, def = false, idx = 1)))
        assertEquals(0, pick?.groupIndex)
    }

    @Test
    fun englishLabelsRecognized() {
        assertTrue(EnglishAudio.isEnglishLanguage("eng"))
        assertTrue(EnglishAudio.isEnglishLanguage("en"))
        assertTrue(EnglishAudio.isEnglishLanguage("English 5.1"))
        assertFalse(EnglishAudio.isEnglishLanguage("ta"))
    }

    @Test
    fun userPickStopsAutoSelect() {
        assertTrue(EnglishAudio.shouldAutoSelect(userPicked = false))
        assertFalse(EnglishAudio.shouldAutoSelect(userPicked = true))
    }
}
