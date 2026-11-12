package com.totaliptv.pro.data.tmdb

import com.totaliptv.pro.data.StreamingService
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceIndexTest {
    private fun movie(name: String, year: Int? = null, tmdb: Int? = null) =
        MediaItem(id = "vod-$name", name = name, streamUrl = "http://x/$name", categoryId = "vod-1",
            kind = ContentKind.VOD, year = year, tmdbId = tmdb)

    private fun show(name: String, year: Int? = null, tmdb: Int? = null) =
        MediaItem(id = "series-$name", name = name, streamUrl = "", categoryId = "series-1",
            kind = ContentKind.SERIES, year = year, tmdbId = tmdb)

    private val index = ServiceIndex(
        listOf(
            TmdbServiceEntry("movie", 1, "The Irishman", year = 2019, services = listOf("netflix")),
            TmdbServiceEntry("movie", 2, "Dune", year = 2021, services = listOf("hbo")),
            TmdbServiceEntry("movie", 3, "Dune", year = 1984, services = listOf("prime")),
            TmdbServiceEntry("movie", 4, "CODA", year = 2021, services = listOf("apple")),
            TmdbServiceEntry("movie", 4, "CODA", year = 2021, services = listOf("prime")),
            TmdbServiceEntry("tv", 10, "The Boys", year = 2019, services = listOf("prime")),
            TmdbServiceEntry("tv", 11, "Severance", year = 2022, services = listOf("apple")),
            TmdbServiceEntry("movie", 5, "Wicked: For Good", year = 2025, services = listOf("netflix")),
            TmdbServiceEntry("movie", 6, "Glass Onion: A Knives Out Mystery", year = 2022, services = listOf("netflix")),
            TmdbServiceEntry("tv", 13, "Formula 1: Drive to Survive", year = 2019, services = listOf("netflix")),
            TmdbServiceEntry("tv", 12, "Money Heist", originalTitle = "La casa de papel", year = 2017,
                services = listOf("netflix"))
        )
    )

    @Test
    fun matchesTitleAndYear() {
        assertEquals(setOf(StreamingService.NETFLIX), index.lookup(movie("The Irishman (2019)")))
        assertEquals(setOf(StreamingService.NETFLIX), index.lookup(movie("EN - Irishman", year = 2020))) // ±1
        assertEquals(setOf(StreamingService.HBO), index.lookup(movie("Dune (2021)")))
        assertEquals(setOf(StreamingService.PRIME), index.lookup(movie("Dune", year = 1984)))
    }

    @Test
    fun wrongYearOrAmbiguousNoYearDoesNotMatch() {
        assertTrue(index.lookup(movie("The Irishman (2005)")).isEmpty())
        // "Dune" without a year maps to two different films: skip rather than guess.
        assertTrue(index.lookup(movie("Dune")).isEmpty())
        // Unique title without year is fine.
        assertEquals(setOf(StreamingService.APPLE), index.lookup(show("Severance")))
    }

    @Test
    fun tmdbIdWinsAndKindsStaySeparate() {
        assertEquals(setOf(StreamingService.HBO), index.lookup(movie("Some Odd Name", tmdb = 2)))
        assertTrue(index.lookup(movie("The Boys (2019)")).isEmpty()) // tv entry, movie item
        assertEquals(setOf(StreamingService.PRIME), index.lookup(show("The Boys")))
        assertEquals(setOf(StreamingService.NETFLIX), index.lookup(show("La Casa de Papel")))
    }

    @Test
    fun duplicatesMergeServices() {
        assertEquals(setOf(StreamingService.APPLE, StreamingService.PRIME), index.lookup(movie("CODA (2021)")))
        assertEquals(10, index.size)
    }

    @Test
    fun shortFormNeedsSameYearAndNeverMatchesWithoutYear() {
        // "Wicked (2024)" is a different film from "Wicked: For Good" (2025).
        assertTrue(index.lookup(movie("Wicked (2024)")).isEmpty())
        assertEquals(setOf(StreamingService.NETFLIX), index.lookup(movie("Glass Onion (2022)")))
        assertEquals(setOf(StreamingService.NETFLIX), index.lookup(movie("Glass Onion: A Knives Out Mystery (2023)")))
        // Series usually have no year: a bare "Formula 1" must not take the Drive to Survive entry.
        assertTrue(index.lookup(show("Formula 1")).isEmpty())
        assertEquals(setOf(StreamingService.NETFLIX), index.lookup(show("Formula 1: Drive to Survive")))
    }
}
