package com.totaliptv.pro.ui.help

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HowToGuideTest {
    private val required = listOf(
        "Getting started", "Live TV", "Movies & Series", "Continue Watching", "Favorites",
        "Search", "Recording (DVR)", "Sleep timer", "Auto-play next", "PIN lock", "Tips & troubleshooting"
    )

    @Test
    fun tvGuideHasEverySectionIncludingGameDay() {
        val titles = HowToGuide.sections(tv = true).map { it.title }
        required.forEach { r -> assertTrue(titles.any { it.startsWith(r) }, "missing $r") }
        assertTrue(titles.any { it.startsWith("Game Day") })
        assertEquals(12, titles.size)
    }

    @Test
    fun phoneGuideLeavesOutTvOnlyScreensAndUsesTaps() {
        val sections = HowToGuide.sections(tv = false)
        val all = sections.joinToString(" ") { it.title + " " + it.body }
        assertFalse(all.contains("Game Day"))
        assertFalse(all.contains("remote", ignoreCase = true))
        assertFalse(all.contains("Guide"))
        assertTrue(all.contains("tap", ignoreCase = true))
        assertEquals(11, sections.size)
    }

    @Test
    fun guideHasNoPricesOrOtherProductNames() {
        val all = (HowToGuide.sections(true) + HowToGuide.sections(false)).joinToString(" ") { it.title + " " + it.body }
        listOf("$", "price", "buy", "VLC", "Google", "Android", "Netflix", "TMDB", "Shield", "Amazon")
            .forEach { assertFalse(all.contains(it, ignoreCase = true), "guide mentions $it") }
    }
}
