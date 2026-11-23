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
        rating: String? = "8.2",
        voteCount: Int? = null
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
        rating = rating,
        voteCount = voteCount
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
    fun picksHighestRatedNotAMidRatedTitle() {
        val mid = movie("nap", "Napoleon", country = "USA", rating = "6.3")
        val highUs = movie("us-hi", "Top American", country = "USA", rating = "8.8")
        val highUk = movie("uk-hi", "Top UK", country = "United Kingdom", rating = "8.9")
        val mid2 = movie("mid2", "Also Mid", country = "USA", rating = "6.1")
        val picked = HeroBannerPick.pick(listOf(mid, mid2, highUk, highUs), "2026-11-23", stored = null)
        assertTrue(picked?.id in setOf("us-hi", "uk-hi"), "picked=${picked?.id}")
        // American preferred only when that title is itself in the top set.
        // 8.8 is within 0.3 of 8.9, so the US title can win; 6.3 cannot.
        assertFalse(picked?.id in setOf("nap", "mid2"))
        val onlyHigh = HeroBannerPick.pick(listOf(mid, highUk, mid2), "2026-11-23", stored = null)
        assertEquals("uk-hi", onlyHigh?.id)
    }

    @Test
    fun ignoresStaleCachedMidRatedIdWhenABetterTopSetExists() {
        val mid = movie("nap", "Napoleon", country = "USA", rating = "6.3")
        val high = movie("hi", "Best", country = "USA", rating = "8.7")
        val cached = HeroBannerPick.pick(
            listOf(mid, high),
            "2026-11-23",
            stored = HeroBannerPick.Stored("2026-11-23", "nap")
        )
        assertEquals("hi", cached?.id)
    }

    @Test
    fun rotatesOnlyInsideTheTopSet() {
        val top = (1..3).map { movie("t-$it", "Top $it", country = "USA", rating = "8.8") }
        val rest = (1..20).map { movie("m-$it", "Mid $it", country = "USA", rating = "6.3") }
        val ids = (1..28).map { d ->
            HeroBannerPick.pick(top + rest, "2026-04-%02d".format(d), stored = null)?.id
        }.toSet()
        assertTrue(ids.all { it.orEmpty().startsWith("t-") }, ids.toString())
        assertTrue(ids.size >= 2, ids.toString())
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
    @Test
    fun skipsImplausibleRatingsAtOrAbove9_5() {
        val fake = movie("fake", "Inflated", country = "USA", rating = "9.9")
        val edge = movie("edge", "Nine Five", country = "USA", rating = "9.5")
        val ok = movie("ok", "Well Known", country = "USA", rating = "8.2")
        val highOk = movie("hi", "Just Under", country = "USA", rating = "9.4")
        val picked = HeroBannerPick.pick(listOf(fake, edge, ok, highOk), "2026-11-23", stored = null)
        assertTrue(picked?.id in setOf("ok", "hi"), "picked=${picked?.id}")
        assertFalse(picked?.id in setOf("fake", "edge"))
        val onlyPlausible = HeroBannerPick.pick(listOf(fake, ok), "2026-11-23", stored = null)
        assertEquals("ok", onlyPlausible?.id)
    }

    @Test
    fun skipsKnownLowVoteCountsButKeepsUnknownCounts() {
        val lowVotes = movie("low", "Obscure High", country = "USA", rating = "9.2", voteCount = 12)
        val known = movie("known", "Famous", country = "USA", rating = "8.1", voteCount = 5_000)
        val unknown = movie("unk", "No Votes Field", country = "USA", rating = "8.3")
        val picked = HeroBannerPick.pick(listOf(lowVotes, known, unknown), "2026-11-23", stored = null)
        assertTrue(picked?.id in setOf("known", "unk"), "picked=${picked?.id}")
        assertFalse(picked?.id == "low")
        assertTrue(HeroBannerPick.hasTooFewVotes(lowVotes))
        assertFalse(HeroBannerPick.hasTooFewVotes(known))
        assertFalse(HeroBannerPick.hasTooFewVotes(unknown))
    }

    @Test
    fun ignoresStaleCachedImplausibleOrLowVoteId() {
        val fake = movie("fake", "Inflated", country = "USA", rating = "9.8")
        val low = movie("low", "Obscure", country = "USA", rating = "9.1", voteCount = 8)
        val ok = movie("ok", "Solid", country = "USA", rating = "8.0")
        val fromFake = HeroBannerPick.pick(
            listOf(fake, ok),
            "2026-11-23",
            stored = HeroBannerPick.Stored("2026-11-23", "fake")
        )
        assertEquals("ok", fromFake?.id)
        val fromLow = HeroBannerPick.pick(
            listOf(low, ok),
            "2026-11-23",
            stored = HeroBannerPick.Stored("2026-11-23", "low")
        )
        assertEquals("ok", fromLow?.id)
    }

    @Test
    fun prefersTheSevenToNineFourBandOverMidRatedTitles() {
        val mid = movie("mid", "Napoleon", country = "USA", rating = "6.3")
        val wellKnown = movie("wk", "Top Gun", country = "USA", rating = "8.2")
        val picked = HeroBannerPick.pick(listOf(mid, wellKnown), "2026-11-23", stored = null)
        assertEquals("wk", picked?.id)
    }

    @Test
    fun rotationStartsAtDailyPickAndCapsAtFive() {
        val titles = (1..7).map { i ->
            movie("m$i", "Movie $i", country = "USA", rating = if (i % 2 == 0) "8.3" else "8.2")
        }
        val day = "2026-11-23"
        val picked = HeroBannerPick.pick(titles, day, stored = null)
        val rotation = HeroBannerPick.rotation(titles, day, stored = null)
        assertEquals(HeroBannerPick.ROTATION_COUNT, rotation.size)
        assertEquals(5, HeroBannerPick.ROTATION_COUNT)
        assertEquals(10_000L, HeroBannerPick.ROTATION_INTERVAL_MS)
        assertEquals(picked?.id, rotation.first().id)
        assertEquals(rotation.map { it.id }.distinct().size, rotation.size)
        val poolIds = HeroBannerPick.topSet(titles).map { it.id }.toSet()
        assertTrue(rotation.all { it.id in poolIds })
        assertEquals(7, poolIds.size)
    }

    @Test
    fun rotationSameDayStoredStartsWithCachedId() {
        val titles = (1..5).map { i ->
            movie("c$i", "Cached $i", country = "USA", rating = "8.2")
        }
        val day = "2026-11-23"
        val stored = HeroBannerPick.Stored(day, "c3")
        val rotation = HeroBannerPick.rotation(titles, day, stored)
        assertEquals(listOf("c3", "c4", "c5", "c1", "c2"), rotation.map { it.id })
        assertEquals("c3", HeroBannerPick.pick(titles, day, stored)?.id)
    }

    @Test
    fun rotationUsesFewerWhenTopSetIsSmallerAndSkipsBadRatings() {
        val mid = movie("mid", "Napoleon", country = "USA", rating = "6.3")
        val implausible = movie("fake", "Fake", country = "USA", rating = "9.9", voteCount = 9000)
        val lowVotes = movie("low", "Low Votes", country = "USA", rating = "8.8", voteCount = 10)
        val a = movie("a", "A", country = "USA", rating = "8.2", voteCount = 200)
        val b = movie("b", "B", country = "USA", rating = "8.1", voteCount = 200)
        val c = movie("c", "C", country = "USA", rating = "8.0", voteCount = 200)
        val rotation = HeroBannerPick.rotation(
            listOf(mid, implausible, lowVotes, a, b, c),
            "2026-11-23",
            stored = null
        )
        assertEquals(3, rotation.size)
        assertTrue(rotation.all { it.id in setOf("a", "b", "c") })
        assertEquals(HeroBannerPick.pick(listOf(mid, implausible, lowVotes, a, b, c), "2026-11-23", null)?.id, rotation.first().id)
    }

}
