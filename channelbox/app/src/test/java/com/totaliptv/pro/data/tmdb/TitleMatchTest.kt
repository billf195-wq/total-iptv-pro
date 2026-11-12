package com.totaliptv.pro.data.tmdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitleMatchTest {
    @Test
    fun extractsYearFromCommonForms() {
        assertEquals(2019, TitleMatch.extractYear("1BR (2019)"))
        assertEquals(2016, TitleMatch.extractYear("EN - Stranger Things [2016]"))
        assertEquals(2022, TitleMatch.extractYear("The Bear - 2022"))
        assertNull(TitleMatch.extractYear("1917"))
        assertNull(TitleMatch.extractYear("2012"))
        assertNull(TitleMatch.extractYear("The Crown"))
        assertEquals(2019, TitleMatch.extractYear("1917 (2019)"))
    }

    @Test
    fun yearFromDate() {
        assertEquals(2019, TitleMatch.yearFromDate("2019-07-25"))
        assertNull(TitleMatch.yearFromDate(""))
        assertNull(TitleMatch.yearFromDate("n/a"))
    }

    @Test
    fun normalizeStripsTagsYearsAndPunctuation() {
        assertEquals("boys", TitleMatch.normalize("EN - The Boys (2019) [4K]"))
        assertEquals("boys", TitleMatch.normalize("The Boys"))
        assertEquals("stranger things", TitleMatch.normalize("|EN| Stranger Things 4K"))
        assertEquals("fast and furious", TitleMatch.normalize("Fast & Furious"))
        assertEquals("amelie", TitleMatch.normalize("Amélie (2001)"))
        assertEquals("ted lasso", TitleMatch.normalize("NF: Ted Lasso - 2020"))
        assertEquals("1917", TitleMatch.normalize("1917"))
        assertEquals("spider man no way home", TitleMatch.normalize("Spider-Man: No Way Home (2021)"))
        assertEquals("ex machina", TitleMatch.normalize("Ex-Machina"))
        assertEquals("boys", TitleMatch.normalize("[MULTI] EN: The Boys"))
        assertEquals("handmaids tale", TitleMatch.normalize("The Handmaid's Tale"))
    }

    @Test
    fun keysIncludeShortFormOnlyWhenSpecific() {
        val k = TitleMatch.keysForTmdb("Mission: Impossible - Dead Reckoning Part One", null)
        assertTrue("mission impossible dead reckoning part one" in k)
        assertTrue("mission" in k) // 7 chars -> specific enough
        assertTrue("mission" in TitleMatch.shortKeysForTmdb("Mission: Impossible - Dead Reckoning Part One", null))
        assertTrue("mission" !in TitleMatch.exactKeysForTmdb("Mission: Impossible - Dead Reckoning Part One", null))
        val short = TitleMatch.keysForTmdb("Up: Extended", null)
        assertTrue("up" !in short)
        val orig = TitleMatch.keysForTmdb("Money Heist", "La casa de papel")
        assertTrue("money heist" in orig && "la casa de papel" in orig)
    }
}
