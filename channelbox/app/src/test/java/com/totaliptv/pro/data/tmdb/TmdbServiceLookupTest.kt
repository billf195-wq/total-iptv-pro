package com.totaliptv.pro.data.tmdb

import com.totaliptv.pro.data.Ratings
import com.totaliptv.pro.data.StreamingService
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbServiceLookupTest {
    @Test
    fun parsesMovieAndTvDiscoverPages() {
        val movies = TmdbServiceLookup.parseDiscover(
            """{"page":1,"total_pages":240,"results":[
              {"id":603,"title":"The Matrix","original_title":"The Matrix","release_date":"1999-03-31"},
              {"id":5,"title":"","release_date":"2000-01-01"},
              {"title":"no id"}]}""",
            "movie", StreamingService.NETFLIX
        )
        assertEquals(240, movies.totalPages)
        assertEquals(1, movies.entries.size)
        val m = movies.entries.single()
        assertEquals(603, m.id); assertEquals(1999, m.year); assertEquals(null, m.originalTitle)
        assertEquals(listOf("netflix"), m.services)

        val tv = TmdbServiceLookup.parseDiscover(
            """{"total_pages":3,"results":[{"id":71446,"name":"Money Heist","original_name":"La casa de papel","first_air_date":"2017-05-02"}]}""",
            "tv", StreamingService.NETFLIX
        )
        assertEquals("La casa de papel", tv.entries.single().originalTitle)
        assertEquals(2017, tv.entries.single().year)
        assertEquals(0, TmdbServiceLookup.parseDiscover("not json", "tv", StreamingService.HBO).totalPages)
    }

    @Test
    fun keyHelpers() {
        assertFalse(TmdbServiceLookup.isV4Token("0123456789abcdef0123456789abcdef"))
        assertTrue(TmdbServiceLookup.isV4Token("eyJhbGciOiJIUzI1NiJ9." + "a".repeat(60) + ".sig"))
        assertEquals("abc", TmdbServiceLookup.normalizeKey(" Bearer abc "))
        val f = TmdbServiceLookup.fingerprint("secret-key")
        assertEquals(8, f.length)
        assertFalse(f.contains("secret"))
        assertNotEquals(f, TmdbServiceLookup.fingerprint("other-key"))
        assertEquals("", TmdbServiceLookup.fingerprint(""))
    }

    @Test
    fun queriesCoverAllServicesBothKinds() {
        for (svc in StreamingService.entries) {
            assertTrue(TmdbServiceLookup.QUERIES.any { it.service == svc && it.type == "movie" })
            assertTrue(TmdbServiceLookup.QUERIES.any { it.service == svc && it.type == "tv" })
        }
    }

    @Test
    fun queriesUseVerifiedUsProviderAndNetworkIds() {
        fun ids(type: String, param: String, svc: StreamingService) =
            TmdbServiceLookup.QUERIES.filter { it.type == type && it.param == param && it.service == svc }
                .map { it.value }
        assertEquals(listOf("8"), ids("movie", "with_watch_providers", StreamingService.NETFLIX))
        assertEquals(listOf("9"), ids("movie", "with_watch_providers", StreamingService.PRIME))
        assertEquals(listOf("1899"), ids("movie", "with_watch_providers", StreamingService.HBO))
        assertEquals(listOf("350"), ids("movie", "with_watch_providers", StreamingService.APPLE))
        assertEquals(listOf("15"), ids("movie", "with_watch_providers", StreamingService.HULU))
        assertEquals(listOf("337"), ids("movie", "with_watch_providers", StreamingService.DISNEY))
        assertEquals(listOf("531|1770|582"), ids("movie", "with_watch_providers", StreamingService.PARAMOUNT))
        assertEquals(listOf("453"), ids("tv", "with_networks", StreamingService.HULU))
        assertEquals(listOf("2739"), ids("tv", "with_networks", StreamingService.DISNEY))
        assertEquals(listOf("4330"), ids("tv", "with_networks", StreamingService.PARAMOUNT))
        assertEquals(listOf("15"), ids("tv", "with_watch_providers", StreamingService.HULU))
        assertEquals(listOf("337"), ids("tv", "with_watch_providers", StreamingService.DISNEY))
        assertEquals(listOf("531|1770|582"), ids("tv", "with_watch_providers", StreamingService.PARAMOUNT))
        assertEquals("US", TmdbServiceLookup.QUERIES.filter { it.service != null }.map { it.watchRegion }.distinct().single())
    }

    @Test
    fun cacheRoundTripAndFreshness() {
        val dir = createTempDir("tmdbcache")
        try {
            val file = File(dir, "tmdb-services.json")
            val now = 1_000_000_000_000L
            val cache = TmdbServiceCache(fetchedAtMs = now, keyFingerprint = "abcd1234",
                entries = listOf(TmdbServiceEntry("movie", 1, "X", year = 2020, services = listOf("netflix"))))
            TmdbServiceLookup.writeCache(file, cache)
            val back = TmdbServiceLookup.readCache(file)
            assertEquals(cache, back)
            assertTrue(TmdbServiceLookup.isFresh(back, "abcd1234", now + 1000))
            assertFalse(TmdbServiceLookup.isFresh(back, "other", now + 1000))
            assertFalse(TmdbServiceLookup.isFresh(back, "abcd1234", now + TmdbServiceLookup.TTL_MS + 1))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parseDiscoverReadsVoteAverageAndCount() {
        val movies = TmdbServiceLookup.parseDiscover(
            """{"total_pages":1,"results":[
              {"id":278,"title":"The Shawshank Redemption","release_date":"1994-09-23",
               "vote_average":8.711,"vote_count":26855}]}""",
            "movie", null
        )
        val m = movies.entries.single()
        assertEquals(emptyList(), m.services)
        assertEquals(8.711, m.voteAverage)
        assertEquals(26855, m.voteCount)

        val tv = TmdbServiceLookup.parseDiscover(
            """{"results":[{"id":1,"name":"Show","first_air_date":"2020-01-01","vote_average":"7.5","vote_count":80}]}""",
            "tv", com.totaliptv.pro.data.StreamingService.APPLE
        )
        assertEquals(listOf("apple"), tv.entries.single().services)
        assertEquals(7.5, tv.entries.single().voteAverage)
        assertEquals(80, tv.entries.single().voteCount)
    }

    @Test
    fun ratingQueriesAreServiceFreeAndUseVoteFloor() {
        assertEquals(2, TmdbServiceLookup.RATING_QUERIES.size)
        for (q in TmdbServiceLookup.RATING_QUERIES) {
            assertEquals(null, q.service)
            assertEquals("vote_average.desc", q.sortBy)
            assertEquals(null, q.watchRegion)
            assertEquals(Ratings.TMDB_MIN_VOTE_COUNT.toString(), q.extraParams["vote_count.gte"])
        }
        assertTrue(TmdbServiceLookup.ALL_QUERIES.size == TmdbServiceLookup.QUERIES.size + 2)
    }

    @Test
    fun oldCacheVersionIsRejectedForFreshnessButUsableForDisplay() {
        val dir = createTempDir("tmdbcache-v1")
        try {
            val file = java.io.File(dir, "tmdb-services.json")
            val stale = TmdbServiceCache(version = 2, fetchedAtMs = 1_000L, keyFingerprint = "abcd1234",
                entries = listOf(TmdbServiceEntry("movie", 1, "X", year = 2020, services = listOf("netflix"))))
            TmdbServiceLookup.writeCache(file, stale)
            assertEquals(null, TmdbServiceLookup.readCache(file))
            val usable = TmdbServiceLookup.readUsableCache(file)
            assertEquals(2, usable?.version)
            assertEquals(1, usable?.entries?.size)
            assertEquals("X", usable?.entries?.single()?.title)
            assertFalse(TmdbServiceLookup.isFresh(usable, "abcd1234", 1_000L + 1000))
            assertEquals(3, TmdbServiceCache.CACHE_VERSION)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun defaultConcurrencyIsLimited() {
        assertEquals(2, TmdbServiceLookup.DEFAULT_CONCURRENCY)
        assertEquals(3, TmdbServiceLookup.MAX_CONCURRENCY)
        assertTrue(TmdbServiceLookup.DEFAULT_CONCURRENCY <= TmdbServiceLookup.MAX_CONCURRENCY)
    }
}
