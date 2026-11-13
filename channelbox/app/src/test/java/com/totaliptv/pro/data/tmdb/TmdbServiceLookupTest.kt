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

    @Test
    fun incompleteCacheIsNotFreshAndReportsReason() {
        val now = 1_000_000_000_000L
        val partial = TmdbServiceCache(
            version = TmdbServiceCache.CACHE_VERSION,
            fetchedAtMs = now,
            keyFingerprint = "abcd1234",
            entries = listOf(TmdbServiceEntry("movie", 1, "X", year = 2020, services = listOf("hulu"))),
            complete = false,
            progress = listOf(TmdbQueryCursor(key = "tv|with_networks|453|popularity.desc|US|hulu", nextPage = 3, done = false))
        )
        assertEquals("incomplete", TmdbServiceLookup.staleReason(partial, "abcd1234", now + 1000))
        assertFalse(TmdbServiceLookup.isFresh(partial, "abcd1234", now + 1000))

        val dir = createTempDir("tmdbcache-partial")
        try {
            val file = File(dir, "tmdb-services.json")
            assertTrue(TmdbServiceLookup.writeCache(file, partial))
            val back = TmdbServiceLookup.readCache(file)
            assertEquals(false, back?.complete)
            assertEquals(1, back?.progress?.size)
            assertEquals(3, back?.progress?.single()?.nextPage)
            assertFalse(TmdbServiceLookup.isFresh(back, "abcd1234", now + 1000))
            // Still usable for display while a fetch resumes.
            assertEquals(1, TmdbServiceLookup.readUsableCache(file)?.entries?.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun finishedCacheWithoutProgressStaysFresh() {
        val now = 1_000_000_000_000L
        val done = TmdbServiceCache(
            fetchedAtMs = now,
            keyFingerprint = "abcd1234",
            entries = listOf(TmdbServiceEntry("tv", 2, "Y", year = 2021, services = listOf("disney")))
        )
        assertEquals(true, done.complete)
        assertEquals(null, TmdbServiceLookup.staleReason(done, "abcd1234", now + 1000))
        assertTrue(TmdbServiceLookup.isFresh(done, "abcd1234", now + 1000))
    }

    @Test
    fun cursorKeyIncludesTypeParamAndService() {
        val q = TmdbServiceLookup.Query("tv", "with_networks", "453", com.totaliptv.pro.data.StreamingService.HULU)
        assertEquals("tv|with_networks|453|popularity.desc|US|hulu", q.cursorKey())
    }

    @Test
    fun serviceQueriesInterleaveMovieAndTvSoSeriesFillsEarly() {
        val q = TmdbServiceLookup.QUERIES
        assertEquals(21, q.size)
        // Each service should appear as movie, then tv network, then tv watch-provider
        // before the next service starts, so Series Hulu/Disney+/Paramount+ are not last.
        fun triple(start: Int, service: com.totaliptv.pro.data.StreamingService) {
            assertEquals("movie", q[start].type)
            assertEquals("with_watch_providers", q[start].param)
            assertEquals(service, q[start].service)
            assertEquals("tv", q[start + 1].type)
            assertEquals("with_networks", q[start + 1].param)
            assertEquals(service, q[start + 1].service)
            assertEquals("tv", q[start + 2].type)
            assertEquals("with_watch_providers", q[start + 2].param)
            assertEquals(service, q[start + 2].service)
        }
        triple(0, com.totaliptv.pro.data.StreamingService.NETFLIX)
        triple(3, com.totaliptv.pro.data.StreamingService.PRIME)
        triple(6, com.totaliptv.pro.data.StreamingService.HBO)
        triple(9, com.totaliptv.pro.data.StreamingService.APPLE)
        triple(12, com.totaliptv.pro.data.StreamingService.HULU)
        triple(15, com.totaliptv.pro.data.StreamingService.DISNEY)
        triple(18, com.totaliptv.pro.data.StreamingService.PARAMOUNT)
        // Series queries for the late services are no longer stuck after every movie query.
        val huluTv = q.indexOfFirst { it.service == com.totaliptv.pro.data.StreamingService.HULU && it.type == "tv" }
        val lastMovie = q.indexOfLast { it.type == "movie" }
        assertTrue(huluTv < lastMovie)
    }

}
