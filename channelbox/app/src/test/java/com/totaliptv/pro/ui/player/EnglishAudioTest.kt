package com.totaliptv.pro.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnglishAudioTest {
    @Test
    fun prefersSupportedEnglishOverForeignDefault() {
        val spanish = candidate(isEnglish = false, isDefault = true, trackIndex = 0)
        val english = candidate(isEnglish = true, isDefault = false, trackIndex = 1)
        val picked = EnglishAudio.pick(listOf(spanish, english))
        assertEquals(1, picked?.trackIndex)
        assertTrue(picked!!.isEnglish)
    }

    @Test
    fun prefersRealEnglishOverCommentary() {
        val commentary = candidate(isEnglish = true, secondary = true, trackIndex = 0)
        val english = candidate(isEnglish = true, secondary = false, trackIndex = 1)
        val picked = EnglishAudio.pick(listOf(commentary, english))
        assertEquals(1, picked?.trackIndex)
        assertFalse(picked!!.secondary)
    }

    @Test
    fun recognizesEnglishLabels() {
        assertTrue(EnglishAudio.isEnglishLanguage("en"))
        assertTrue(EnglishAudio.isEnglishLanguage("eng"))
        assertTrue(EnglishAudio.isEnglishLanguage("en-US"))
        assertTrue(EnglishAudio.isEnglishLanguage("English"))
        assertFalse(EnglishAudio.isEnglishLanguage("es"))
        assertFalse(EnglishAudio.isEnglishLanguage(null))
    }

    private fun candidate(
        isEnglish: Boolean,
        isDefault: Boolean = false,
        secondary: Boolean = false,
        trackIndex: Int
    ) = EnglishAudio.Candidate(
        isEnglish = isEnglish,
        supported = true,
        hasPlayableChannels = true,
        isDefault = isDefault,
        secondary = secondary,
        selected = false,
        groupIndex = 0,
        trackIndex = trackIndex
    )
}
