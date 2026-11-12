package com.totaliptv.pro.data

import com.totaliptv.pro.data.m3u.M3uParser
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RatingsTest {
    @Test
    fun xtreamPrefersTenScaleThenFiveBased() {
        assertEquals("8.2", Ratings.normalizeXtream("8.2", "4.1"))
        assertEquals("7.5", Ratings.normalizeXtream("7,5", null))
        assertEquals("8.0", Ratings.normalizeXtream("", "4"))
        assertEquals("8.0", Ratings.normalizeXtream("N/A", "4.0"))
        assertNull(Ratings.normalizeXtream("0", "0"))
        assertNull(Ratings.normalizeXtream(null, null))
        assertNull(Ratings.normalizeXtream("junk", "junk"))
    }

    @Test
    fun m3uNumericOnly() {
        assertEquals("7.4", Ratings.normalizeM3u("7.4"))
        assertEquals("7.4", Ratings.normalizeM3u("7,4"))
        assertEquals("7.0", Ratings.normalizeM3u("3.5/5"))
        assertEquals("7.4", Ratings.normalizeM3u("74/100"))
        assertEquals("7.4", Ratings.normalizeM3u("74"))
        assertNull(Ratings.normalizeM3u("PG-13"))
        assertNull(Ratings.normalizeM3u("TV-MA"))
        assertNull(Ratings.normalizeM3u("0"))
        assertNull(Ratings.normalizeM3u(""))
        assertNull(Ratings.normalizeM3u("500"))
    }

    private fun m(name: String, rating: String?) =
        MediaItem(id = name, name = name, streamUrl = "u", categoryId = "c", kind = ContentKind.VOD, rating = rating)

    @Test
    fun topRatedHighestFirstUnratedLastTiesAz() {
        val sorted = Ratings.sortTopRated(
            listOf(
                m("Zulu", null),
                m("beta", "7.0"),
                m("Alpha", "7.0"),
                m("Gamma", "9.1"),
                m("Delta", "0"),
                m("Echo", "N/A"),
                m("Apex", null),
                m("Low", "2.5")
            )
        )
        assertEquals(listOf("Gamma", "Alpha", "beta", "Low", "Apex", "Delta", "Echo", "Zulu"), sorted.map { it.name })
    }

    @Test
    fun m3uParserReadsTvgRatingForVodOnly() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-rating="8.1" group-title="Netflix Movies",Movie A
            http://h/movie/u/p/1.mp4
            #EXTINF:-1 tvg-rating="PG-13" group-title="Movies",Movie B
            http://h/movie/u/p/2.mkv
            #EXTINF:-1 tvg-rating="9" group-title="News",News One
            http://h/live/u/p/3.ts
        """.trimIndent()
        val parsed = M3uParser.parse(text)
        val byName = parsed.items.associateBy { it.name }
        assertEquals("8.1", byName["Movie A"]?.rating)
        assertNull(byName["Movie B"]?.rating)
        assertNull(byName["News One"]?.rating)
    }

    @Test
    fun tmdbScoreRequiresVotesAndRange() {
        assertEquals(8.432, Ratings.tmdbScore(8.432, 50))
        assertEquals(8.0, Ratings.tmdbScore(8.0, 10_000))
        assertEquals("8.4", Ratings.tmdbDisplay(8.432, 50))
        assertEquals("8.4", Ratings.formatTen(8.432))
        assertNull(Ratings.tmdbScore(9.9, 49))
        assertNull(Ratings.tmdbScore(9.9, 0))
        assertNull(Ratings.tmdbScore(9.9, null))
        assertNull(Ratings.tmdbScore(null, 1_000))
        assertNull(Ratings.tmdbScore(0.0, 1_000))
        assertNull(Ratings.tmdbScore(11.0, 1_000))
        assertEquals(50, Ratings.TMDB_MIN_VOTE_COUNT)
    }

    @Test
    fun scorePrefersProviderOverTmdbFallback() {
        assertEquals(7.0, Ratings.score(m("A", "7.0"), tmdbFallback = 9.5))
        assertEquals(9.5, Ratings.score(m("B", null), tmdbFallback = 9.5))
        assertEquals(9.5, Ratings.score(m("C", "0"), tmdbFallback = 9.5))
        assertEquals(9.5, Ratings.score(m("D", "N/A"), tmdbFallback = 9.5))
        assertNull(Ratings.score(m("E", null), tmdbFallback = null))
        assertNull(Ratings.score(m("F", null)))
    }

    @Test
    fun topRatedAndSortUseMergedRatingAndStampFallback() {
        val items = listOf(
            m("Gamma", "9.1"),
            m("UnratedHit", null),
            m("Alpha", "7.0"),
            m("Obscure", null),
            m("Zed", null)
        )
        val tmdb: (MediaItem) -> Double? = { item ->
            when (item.name) {
                "UnratedHit" -> 8.6
                "Obscure" -> Ratings.tmdbScore(9.9, 12) // too few votes
                "Gamma" -> 4.0 // must not beat the provider 9.1
                else -> null
            }
        }
        val top = Ratings.topRatedOnly(items, 500, tmdb)
        assertEquals(listOf("Gamma", "UnratedHit", "Alpha"), top.map { it.name })
        assertEquals("8.6", top[1].rating) // stamped for badges
        assertEquals("9.1", top[0].rating) // provider left alone
        assertEquals(listOf("Gamma", "UnratedHit"), Ratings.topRatedOnly(items, 2, tmdb).map { it.name })

        val sorted = Ratings.sortTopRated(items, tmdb)
        assertEquals(listOf("Gamma", "UnratedHit", "Alpha", "Obscure", "Zed"), sorted.map { it.name })
        assertEquals("8.6", sorted[1].rating)
        assertNull(sorted.first { it.name == "Obscure" }.displayRating())
    }
}
