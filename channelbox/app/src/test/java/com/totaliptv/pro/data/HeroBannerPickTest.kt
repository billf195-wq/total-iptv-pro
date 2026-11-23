package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import java.io.File
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeroBannerPickTest {
    private fun movie(
        id: String,
        name: String,
        playable: Boolean = true,
        country: String? = null,
        backdrop: String? = null,
        poster: String? = null,
        year: Int? = 2022,
        rating: String? = "8.2"
    ) = MediaItem(
        id = id,
        name = name,
        streamUrl = if (playable) "playable://$id" else "",
        categoryId = "vod-1",
        kind = ContentKind.VOD,
        posterUrl = poster,
        year = year,
        backdropUrl = backdrop,
        country = country,
        rating = rating
    )

    @Test
    fun prefersAmericanWhenCountryExistsOtherwiseTopRated() {
        val us = movie("us-1", "US One", country = "USA")
        val uk = movie("uk-1", "UK One", country = "United Kingdom")
        val bare = movie("bare-1", "No Country")
        val pickedUs = HeroBannerPick.pick(listOf(uk, us, bare), "2026-11-23", stored = null)
        assertEquals("us-1", pickedUs?.id)

        val noCountry = HeroBannerPick.pick(listOf(uk, bare), "2026-11-23", stored = null)
        assertTrue(noCountry?.id in setOf("uk-1", "bare-1"))
    }

    @Test
    fun skipsUnplayableAndNonMovies() {
        val series = movie("s-1", "Series-like").copy(kind = ContentKind.SERIES, streamUrl = "playable://s-1")
        val dead = movie("dead", "Dead", playable = false, country = "USA")
        val ok = movie("ok", "Playable", country = "US")
        val picked = HeroBannerPick.pick(listOf(series, dead, ok), "2026-11-23", stored = null)
        assertEquals("ok", picked?.id)
        assertTrue(HeroBannerPick.isPlayableMovie(ok))
        assertFalse(HeroBannerPick.isPlayableMovie(dead))
        assertFalse(HeroBannerPick.isPlayableMovie(series))
    }

    @Test
    fun sameDayIsStableAndCachedIdWins() {
        val a = movie("a", "A", country = "USA")
        val b = movie("b", "B", country = "United States")
        val c = movie("c", "C", country = "US")
        val list = listOf(a, b, c)
        val first = HeroBannerPick.pick(list, "2026-01-02", stored = null)
        val again = HeroBannerPick.pick(list, "2026-01-02", stored = null)
        assertEquals(first?.id, again?.id)
        val cached = HeroBannerPick.pick(list, "2026-01-02", stored = HeroBannerPick.Stored("2026-01-02", "c"))
        assertEquals("c", cached?.id)
        val newDay = HeroBannerPick.pick(list, "2026-01-03", stored = HeroBannerPick.Stored("2026-01-02", "c"))
        // A new day may still land on the same id, but must ignore the stale cache when choosing the pool.
        assertTrue(newDay != null)
        assertTrue(newDay!!.id in setOf("a", "b", "c"))
    }

    @Test
    fun differentDaysCanChangeThePick() {
        val list = (1..20).map { movie("m-$it", "Title $it", country = "USA") }
        val days = (1..28).map { d ->
            HeroBannerPick.pick(list, "2026-04-%02d".format(d), stored = null)?.id
        }.toSet()
        assertTrue(days.size >= 2, "expected more than one daily pick across a month")
    }

    @Test
    fun imagePrefersBackdropThenPosterAndNeverNeedsBoth() {
        val both = movie("x", "X", backdrop = "backdrop.jpg", poster = "poster.jpg")
        val posterOnly = movie("y", "Y", poster = "poster.jpg")
        val none = movie("z", "Z")
        assertEquals("backdrop.jpg", HeroBannerPick.imageUrl(both))
        assertEquals("poster.jpg", HeroBannerPick.imageUrl(posterOnly))
        assertNull(HeroBannerPick.imageUrl(none))
        assertEquals("2022  ·  ★ 8.2", HeroBannerPick.metaLine(both))
    }

    @Test
    fun fileCacheRoundTripAndDayString() {
        val dir = File.createTempFile("hero-pick", "dir").apply {
            delete()
            mkdirs()
        }
        try {
            assertNull(HeroBannerPick.read(dir))
            HeroBannerPick.write(dir, HeroBannerPick.Stored("2026-11-23", "vod-9"))
            assertEquals(HeroBannerPick.Stored("2026-11-23", "vod-9"), HeroBannerPick.read(dir))
            assertEquals("hero-banner-pick.txt", HeroBannerPick.FILE_NAME)
            assertFalse(HeroBannerPick.FILE_NAME.contains("vod-catalog-cache"))
            val day = HeroBannerPick.dayString(1_763_884_800_000L, ZoneOffset.UTC) // 2025-11-23
            assertTrue(day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun americanTokens() {
        assertTrue(HeroBannerPick.isAmerican("USA"))
        assertTrue(HeroBannerPick.isAmerican("United States"))
        assertTrue(HeroBannerPick.isAmerican("US / UK"))
        assertFalse(HeroBannerPick.isAmerican(null))
        assertFalse(HeroBannerPick.isAmerican(""))
        assertFalse(HeroBannerPick.isAmerican("United Kingdom"))
        assertFalse(HeroBannerPick.isAmerican("France"))
    }
}
