package com.totaliptv.pro.data.tmdb

import com.totaliptv.pro.data.StreamingService
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
}
