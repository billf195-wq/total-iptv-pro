package com.totaliptv.pro.desktop.ui

import com.totaliptv.pro.desktop.artwork.TmdbTitle
import com.totaliptv.pro.desktop.data.Catalog
import com.totaliptv.pro.desktop.data.ContentKind
import com.totaliptv.pro.desktop.data.MediaItem
import com.totaliptv.pro.desktop.data.ResumeStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeDedupeTest {

    @Test
    fun homeRowsKeepOneCopyPreferringResumeThenQualityThenCatalogOrder() {
        assertEquals("alpha|2024", TmdbTitle.signature(movie("x", "EN - Alpha (2024) 4K HEVC")))
        assertEquals("alpha|2024", TmdbTitle.signature(movie("y", "[EN] Alpha (2024) [FHD]")))
        assertEquals("alpha|2024", TmdbTitle.signature(movie("z", "HD Alpha (2024)")))
        assertEquals("inception|2010", TmdbTitle.signature(movie("i", "EN - Inception (2010) [HEVC]")))

        val uhd = movie("uhd", "EN - Alpha (2024) 4K HEVC", rating = "9.4")
        val fhd = movie("fhd", "Alpha (2024) FHD", rating = "9.4")
        val hd = movie("hd", "HD Alpha (2024)", rating = "9.4")
        val others = listOf("Beta", "Charlie", "Delta", "Echo", "Foxtrot", "Golf").mapIndexed { index, title ->
            movie(title.lowercase(), "$title (2024)", rating = "${8.0 - index * 0.1}")
        }
        val catalog = Catalog(vodItems = listOf(uhd, fhd, hd) + others)
        val row = pickTopRatedMovies(catalog)
        assertEquals("uhd", row.items.first().id)
        assertEquals(row.items.size, row.items.map { TmdbTitle.signature(it) }.distinct().size)
        assertEquals(1, row.items.count { TmdbTitle.signature(it) == "alpha|2024" })
        assertTrue(row.items.map { it.id }.containsAll(others.map { it.id }))

        val watched = pickTopRatedMovies(
            catalog,
            resume = listOf(resume("fhd", "Alpha (2024) FHD"))
        )
        assertEquals("fhd", watched.items.first { TmdbTitle.signature(it) == "alpha|2024" }.id)

        val listed = HomeDedupe.dedupe(
            listOf(
                movie("later", "EN - Inception (2010) [HEVC]"),
                movie("first", "[EN] Inception (2010)")
            ),
            catalogIndex = mapOf("first" to 0, "later" to 1)
        )
        assertEquals("first", listed.single().id)

        val parted = HomeDedupe.dedupe(
            listOf(movie("dune", "Dune (2021)"), movie("two", "Dune (2024)"))
        )
        assertEquals(listOf("dune", "two"), parted.map { it.id })
    }

    @Test
    fun resolvedTmdbIdCollapsesCopiesAndContinueWatchingIsNotRepeated() {
        val matrix = movie("plain", "The Matrix (1999)", rating = "9.0", year = 1999)
        val reloaded = movie("tagged", "EN - The Matrix (1999) 1080p", rating = "9.0", year = 1999, tmdbId = "603")
        val other = movie("other", "Arrival (2016)", rating = "8.0", year = 2016)
        val byId = HomeDedupe.dedupe(
            listOf(matrix, reloaded),
            resolvedId = { item -> if (item.id == "plain" || item.id == "tagged") "603" else null }
        )
        assertEquals(listOf("tagged"), byId.map { it.id })

        val shows = listOf(
            series("a", "EN - The Office (2005) 4K"),
            series("b", "The Office (2005) FHD"),
            series("c", "Parks and Recreation (2009)")
        )
        val seriesRow = pickTopRatedSeries(Catalog(seriesItems = shows))
        assertEquals(listOf("a", "c"), seriesRow.items.map { it.id })

        val linked = HomeDedupe.dedupe(
            listOf(matrix, reloaded, other),
            shownElsewhere = listOf(reloaded),
            resolvedId = { item ->
                if (item.id == "plain" || item.id == "tagged") "603" else null
            }
        )
        assertEquals(listOf("other"), linked.map { it.id })

        val alpha = movie("plain", "Alpha (2024)", rating = "9.0")
        val alphaHd = movie("tagged", "EN - Alpha (2024) 1080p", rating = "9.0")
        val beta = movie("beta", "Beta (2024)", rating = "8.5")
        val rest = (1..6).map { movie("fill-$it", "Filler $it (2024)", rating = "7.0") }
        val hidden = pickTopRatedMovies(
            Catalog(vodItems = listOf(alpha, alphaHd, beta) + rest),
            shownElsewhere = listOf(alphaHd)
        )
        assertFalse(hidden.items.any { TmdbTitle.signature(it) == "alpha|2024" })
        assertEquals("beta", hidden.items.first().id)

        val older = resume("uhd", "Alpha 4K")
        val newer = resume("fhd", "Alpha FHD").copy(lastOpenedEpochMs = older.lastOpenedEpochMs + 5)
        val fhd = movie("fhd", "Alpha (2024) FHD")
        val uhd = movie("uhd", "Alpha (2024) 4K")
        val continued = HomeDedupe.dedupeContinue(listOf(newer to fhd, older to uhd))
        assertEquals(listOf("fhd"), continued.map { it.second?.id })
    }

    @Test
    fun movieGridDropsOnlyIdenticalIds() {
        val first = movie("same", "EN - Alpha (2024) 4K")
        val copy = movie("same", "Alpha (2024) FHD")
        val other = movie("other", "Alpha (2024) HD")
        assertEquals(listOf("same", "other"), HomeDedupe.dropIdenticalIds(listOf(first, copy, other)).map { it.id })
        assertEquals("EN - Alpha (2024) 4K", HomeDedupe.dropIdenticalIds(listOf(first, copy)).single().name)
        val home = HomeDedupe.dedupe(listOf(first, other))
        assertEquals(listOf("same"), home.map { it.id })
    }

    private fun resume(id: String, name: String) = ResumeStore.ResumeEntry(
        key = id,
        catalogId = id,
        name = name,
        kind = ContentKind.VOD.name
    )

    private fun movie(
        id: String,
        name: String,
        rating: String = "8.0",
        year: Int? = 2024,
        tmdbId: String? = null
    ) = MediaItem(
        id = id,
        name = name,
        streamUrl = "http://example/$id",
        categoryId = "vod",
        kind = ContentKind.VOD,
        rating = rating,
        country = "United States",
        year = year,
        tmdbId = tmdbId,
        playable = true
    )

    private fun series(id: String, name: String) = MediaItem(
        id = id,
        name = name,
        streamUrl = "",
        categoryId = "series",
        kind = ContentKind.SERIES,
        rating = "8.5",
        country = "United States",
        year = if (name.contains("2005")) 2005 else 2009,
        playable = false
    )
}
