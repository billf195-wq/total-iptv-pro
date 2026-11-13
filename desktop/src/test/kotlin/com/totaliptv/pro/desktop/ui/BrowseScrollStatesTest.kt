package com.totaliptv.pro.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowseScrollStatesTest {
    @Test
    fun keysAreStableAndDistinct() {
        assertEquals("home", BrowseScrollStates.HOME)
        assertEquals("home:continue", BrowseScrollStates.HOME_CONTINUE)
        assertEquals("live:all", BrowseScrollStates.live(null))
        assertEquals("live:news", BrowseScrollStates.live("news"))
        assertEquals("movies:all:AZ", BrowseScrollStates.movies(null, "AZ"))
        assertEquals("movies:netflix:RECENT", BrowseScrollStates.movies("netflix", "RECENT"))
        assertEquals("series:hbo:ZA", BrowseScrollStates.series("hbo", "ZA"))
        assertEquals("chips:VOD", BrowseScrollStates.chips("VOD"))
        assertFalse(BrowseScrollStates.movies("a", "AZ") == BrowseScrollStates.series("a", "AZ"))
    }

    @Test
    fun unusedSnapshotIsOrigin() {
        val states = BrowseScrollStates()
        val spot = states.snapshot("movies:all:AZ")
        assertTrue(spot.isOrigin)
        val seeded = BrowseScrollStates(mapOf("home" to ScrollSpot(5, 20)))
        assertEquals(ScrollSpot(5, 20), seeded.snapshot("home"))
    }
}
