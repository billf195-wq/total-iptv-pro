package com.totaliptv.pro.data.tmdb

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbPlotLookupTest {

    private fun series(name: String = "The Show", plot: String? = null, year: Int? = null, tmdbId: Int? = null) =
        MediaItem(
            id = "series-1",
            name = name,
            streamUrl = "http://example.test/series/u/p/1.mp4",
            categoryId = "series-drama",
            kind = ContentKind.SERIES,
            plot = plot,
            year = year,
            tmdbId = tmdbId
        )

    @Test
    fun parseDetailsReadsOverviewYearAndRating() {
        val body = """
            {"id":1396,"name":"Breaking Bad","overview":"A chemistry teacher.","first_air_date":"2008-01-20","vote_average":8.9}
        """.trimIndent()
        val d = TmdbPlotLookup.parseDetails(body)
        assertEquals("A chemistry teacher.", d.plot)
        assertEquals(2008, d.year)
        assertEquals("8.9", d.rating)
        assertEquals(1396, d.tmdbId)
    }

    @Test
    fun parseSearchPrefersAnExactTitleAndYear() {
        val body = """
            {"results":[
              {"id":1,"name":"The Office","first_air_date":"2001-07-09","overview":"UK office."},
              {"id":2,"name":"The Office","first_air_date":"2005-03-24","overview":"US office."}
            ]}
        """.trimIndent()
        val d = TmdbPlotLookup.parseSearch(body, "The Office", year = 2005)
        assertEquals("US office.", d.plot)
        assertEquals(2005, d.year)
        assertEquals(2, d.tmdbId)
    }

    @Test
    fun parseEpisodeOverviewReadsThePlot() {
        val body = """{"name":"Pilot","overview":"Walter begins.","season_number":1,"episode_number":1}"""
        assertEquals("Walter begins.", TmdbPlotLookup.parseEpisodeOverview(body))
        assertNull(TmdbPlotLookup.parseEpisodeOverview("""{"name":"Pilot","overview":"  "}"""))
    }

    @Test
    fun applyFillsOnlyBlankFields() {
        val kept = TmdbPlotLookup.apply(
            series(plot = "Provider plot", year = 2010, tmdbId = 9),
            TmdbPlotLookup.Details(plot = "TMDB plot", year = 2008, rating = "8.0", tmdbId = 1396)
        )
        assertEquals("Provider plot", kept.plot)
        assertEquals(2010, kept.year)
        assertEquals(9, kept.tmdbId)

        val filled = TmdbPlotLookup.apply(
            series(),
            TmdbPlotLookup.Details(plot = "TMDB plot", year = 2008, rating = "8.2", tmdbId = 1396)
        )
        assertEquals("TMDB plot", filled.plot)
        assertEquals(2008, filled.year)
        assertEquals("8.2", filled.rating)
        assertEquals(1396, filled.tmdbId)
        assertTrue(filled.displayRating() == "8.2")
    }
}
