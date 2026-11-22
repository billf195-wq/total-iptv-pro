package com.totaliptv.pro.data.tmdb

import com.totaliptv.pro.data.Ratings
import com.totaliptv.pro.data.StreamingService
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
        // BET+ has no current TMDB US provider id, so it is keyword-only.
        val tmdbServices = StreamingService.entries.filter { it != StreamingService.BET }
        for (svc in tmdbServices) {
            assertTrue(TmdbServiceLookup.QUERIES.any { it.service == svc && it.type == "movie" })
            assertTrue(TmdbServiceLookup.QUERIES.any { it.service == svc && it.type == "tv" })
        }
        assertTrue(TmdbServiceLookup.QUERIES.none { it.service == StreamingService.BET })
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
        assertEquals(listOf("386|387"), ids("movie", "with_watch_providers", StreamingService.PEACOCK))
        assertEquals(listOf("386|387"), ids("tv", "with_watch_providers", StreamingService.PEACOCK))
        assertEquals(listOf("43"), ids("movie", "with_watch_providers", StreamingService.STARZ))
        assertEquals(listOf("43"), ids("tv", "with_watch_providers", StreamingService.STARZ))
        assertEquals(listOf("34"), ids("movie", "with_watch_providers", StreamingService.MGM))
        assertEquals(listOf("526"), ids("movie", "with_watch_providers", StreamingService.AMC))
        assertEquals(listOf("520"), ids("movie", "with_watch_providers", StreamingService.DISCOVERY))
        assertEquals(listOf("520"), ids("tv", "with_watch_providers", StreamingService.DISCOVERY))
        assertEquals(listOf("283"), ids("movie", "with_watch_providers", StreamingService.CRUNCHYROLL))
        assertEquals(listOf("283"), ids("tv", "with_watch_providers", StreamingService.CRUNCHYROLL))
        assertEquals(emptyList(), ids("movie", "with_watch_providers", StreamingService.BET))
        assertEquals("US", TmdbServiceLookup.QUERIES.filter { it.service != null }.map { it.watchRegion }.distinct().single())
    }

    @Test
    fun cacheRoundTripAndFreshness() {
        val dir = createTempDir("tmdbcache")
        try {
            val file = File(dir, "tmdb-services.json")
            val now = 1_000_000_000_000L
            val cache = TmdbServiceCache(fetchedAtMs = now, keyFingerprint = "abcd1234",
                entries = listOf(TmdbServiceEntry("movie", 1, "X", year = 2020, services = listOf("netflix"))),
                queriedServices = TmdbServiceLookup.serviceKeys().sorted())
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
            assertEquals(4, TmdbServiceCache.CACHE_VERSION)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun defaultConcurrencyIsLimited() {
        assertEquals(1, TmdbServiceLookup.DEFAULT_CONCURRENCY)
        assertEquals(2, TmdbServiceLookup.MAX_CONCURRENCY)
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
        // Only Hulu is on the file, so the missing new/other services are the reason.
        assertEquals("providers", TmdbServiceLookup.staleReason(partial, "abcd1234", now + 1000))
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
            entries = listOf(TmdbServiceEntry("tv", 2, "Y", year = 2021, services = listOf("disney"))),
            queriedServices = TmdbServiceLookup.serviceKeys().sorted()
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
        assertEquals(33, q.size)
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
        fun pair(start: Int, service: com.totaliptv.pro.data.StreamingService) {
            assertEquals("movie", q[start].type)
            assertEquals("with_watch_providers", q[start].param)
            assertEquals(service, q[start].service)
            assertEquals("tv", q[start + 1].type)
            assertEquals("with_watch_providers", q[start + 1].param)
            assertEquals(service, q[start + 1].service)
        }
        pair(21, com.totaliptv.pro.data.StreamingService.PEACOCK)
        pair(23, com.totaliptv.pro.data.StreamingService.STARZ)
        pair(25, com.totaliptv.pro.data.StreamingService.MGM)
        pair(27, com.totaliptv.pro.data.StreamingService.AMC)
        pair(29, com.totaliptv.pro.data.StreamingService.DISCOVERY)
        pair(31, com.totaliptv.pro.data.StreamingService.CRUNCHYROLL)
        // Series queries for the late services are no longer stuck after every movie query.
        val huluTv = q.indexOfFirst { it.service == com.totaliptv.pro.data.StreamingService.HULU && it.type == "tv" }
        val lastMovie = q.indexOfLast { it.type == "movie" }
        assertTrue(huluTv < lastMovie)
    }

    @Test
    fun v3CacheWithOnlyOriginalServicesIsStaleAfterVersionBump() {
        val now = 1_000_000_000_000L
        val originalSeven = listOf("netflix", "prime", "hbo", "apple", "hulu", "disney", "paramount")
        val v3 = TmdbServiceCache(
            version = 3,
            fetchedAtMs = now,
            keyFingerprint = "abcd1234",
            entries = originalSeven.mapIndexed { i, key ->
                TmdbServiceEntry("movie", i + 1, key, year = 2020, services = listOf(key))
            },
            complete = true
        )
        // v3 is still a usable seed; the actionable reason is the missing new providers.
        assertEquals("providers", TmdbServiceLookup.staleReason(v3, "abcd1234", now + 1000))
        assertFalse(TmdbServiceLookup.isFresh(v3, "abcd1234", now + 1000))
        // Still shown immediately while a refresh runs.
        val dir = createTempDir("tmdbcache-v3")
        try {
            val file = File(dir, "tmdb-services.json")
            TmdbServiceLookup.writeCache(file, v3)
            assertEquals(null, TmdbServiceLookup.readCache(file))
            val usable = TmdbServiceLookup.readUsableCache(file)
            assertEquals(3, usable?.version)
            assertEquals(7, usable?.entries?.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cacheMissingNewProviderQueriesIsStaleEvenAtCurrentVersion() {
        val now = 1_000_000_000_000L
        val originalSeven = listOf("netflix", "prime", "hbo", "apple", "hulu", "disney", "paramount")
        val partial = TmdbServiceCache(
            version = TmdbServiceCache.CACHE_VERSION,
            fetchedAtMs = now,
            keyFingerprint = "abcd1234",
            entries = listOf(TmdbServiceEntry("movie", 1, "X", year = 2020, services = listOf("netflix"))),
            complete = true,
            queriedServices = originalSeven
        )
        val expected = originalSeven.toSet() + "peacock"
        assertEquals("providers", TmdbServiceLookup.staleReason(partial, "abcd1234", now + 1000, expectedServices = expected))
        assertFalse(TmdbServiceLookup.isFresh(partial, "abcd1234", now + 1000, expectedServices = expected))

        // Legacy file with no queriedServices list: infer from entry hits only.
        val inferred = TmdbServiceCache(
            version = TmdbServiceCache.CACHE_VERSION,
            fetchedAtMs = now,
            keyFingerprint = "abcd1234",
            entries = originalSeven.mapIndexed { i, key ->
                TmdbServiceEntry("tv", i + 1, key, year = 2021, services = listOf(key))
            },
            complete = true
        )
        assertEquals("providers", TmdbServiceLookup.staleReason(inferred, "abcd1234", now + 1000, expectedServices = expected))
        assertFalse("peacock" in TmdbServiceLookup.fetchedServiceKeys(inferred))
    }

    @Test
    fun cacheThatQueriedEveryCurrentServiceStaysFresh() {
        val now = 1_000_000_000_000L
        val done = TmdbServiceCache(
            fetchedAtMs = now,
            keyFingerprint = "abcd1234",
            entries = listOf(TmdbServiceEntry("movie", 1, "X", year = 2020, services = listOf("peacock"))),
            queriedServices = TmdbServiceLookup.serviceKeys().sorted(),
            appVersion = "1.4.111"
        )
        assertEquals(null, TmdbServiceLookup.staleReason(done, "abcd1234", now + 1000, currentAppVersion = "1.4.111"))
        assertTrue(TmdbServiceLookup.isFresh(done, "abcd1234", now + 1000, currentAppVersion = "1.4.111"))
        assertEquals(null, TmdbServiceLookup.staleReason(done, "abcd1234", now + 1000, currentAppVersion = "1.4.112"))
        assertTrue(TmdbServiceLookup.isFresh(done, "abcd1234", now + 1000, currentAppVersion = "1.4.112"))
    }



    private val originalSeven = listOf("netflix", "prime", "hbo", "apple", "hulu", "disney", "paramount")

    private fun entry(id: Int, service: String, type: String = "movie") =
        TmdbServiceEntry(type, id, service, year = 2020, services = listOf(service))

    private fun partialV4From111(): TmdbServiceCache {
        val now = 1_000_000_000_000L
        val progress = TmdbServiceLookup.ALL_QUERIES.map { q ->
            val key = q.service?.key
            when {
                key in originalSeven && key == "netflix" && q.type == "movie" ->
                    TmdbQueryCursor(q.cursorKey(), nextPage = 9, done = false)
                key in originalSeven ->
                    TmdbQueryCursor(q.cursorKey(), nextPage = 2, done = false)
                else ->
                    TmdbQueryCursor(q.cursorKey(), nextPage = 1, done = false)
            }
        }
        return TmdbServiceCache(
            version = 4,
            fetchedAtMs = now,
            keyFingerprint = "abcd1234",
            entries = originalSeven.mapIndexed { i, key -> entry(i + 1, key) },
            complete = false,
            progress = progress,
            // 1.4.111 wrote the full list before the new queries actually ran.
            queriedServices = TmdbServiceLookup.serviceKeys().sorted(),
            appVersion = "1.4.111"
        )
    }

    @Test
    fun planFillMissingFromPartialV4SkipsOriginalSevenAndStartsNewServices() {
        val cache = partialV4From111()
        val now = cache.fetchedAtMs + 60_000L
        val plan = TmdbServiceLookup.planFetch(cache, "abcd1234", now, force = false)
        assertEquals(TmdbServiceLookup.FetchPlan.Kind.FillMissing, plan.kind)
        assertEquals("providers", plan.reason)
        assertEquals(cache.fetchedAtMs, plan.fetchedAtMs)
        assertFalse(plan.fullRecheck)
        assertEquals(originalSeven.toSet(), plan.completedServices)
        val runServices = plan.queries.mapNotNull { it.service?.key }.distinct()
        assertTrue("peacock" in runServices)
        assertEquals("peacock", runServices.first())
        assertTrue("starz" in runServices && "crunchyroll" in runServices)
        assertTrue(runServices.none { it in originalSeven })
        assertTrue(plan.queries.none { it.service == null }) // ratings wait for the 7-day pass
        // Original-seven cursors are marked done so a restart will not walk Netflix again.
        val netflixCursors = plan.progress.filter { it.key.endsWith("|netflix") }
        assertTrue(netflixCursors.isNotEmpty() && netflixCursors.all { it.done })
        val peacockCursors = plan.progress.filter { it.key.endsWith("|peacock") }
        assertTrue(peacockCursors.isNotEmpty() && peacockCursors.all { !it.done && it.nextPage == 1 })
    }

    @Test
    fun planResumesIncompleteNewServiceAfterRestart() {
        val base = partialV4From111()
        val peacockQs = TmdbServiceLookup.QUERIES.filter { it.service?.key == "peacock" }
        val progress = base.progress.map { c ->
            if (c.key == peacockQs.first().cursorKey()) c.copy(nextPage = 5, done = false) else c
        }
        val cache = base.copy(
            entries = base.entries + entry(100, "peacock"),
            progress = progress,
            completedServices = originalSeven
        )
        val plan = TmdbServiceLookup.planFetch(cache, "abcd1234", cache.fetchedAtMs + 1000)
        assertEquals(TmdbServiceLookup.FetchPlan.Kind.FillMissing, plan.kind)
        assertFalse("peacock" in plan.completedServices)
        val resume = plan.progress.first { it.key == peacockQs.first().cursorKey() }
        assertEquals(5, resume.nextPage)
        assertFalse(resume.done)
        assertTrue(plan.queries.any { it.cursorKey() == peacockQs.first().cursorKey() })
        assertTrue(plan.queries.none { it.service?.key in originalSeven })
    }

    @Test
    fun planDoesNotStartFromZeroWhenUsableCacheExists() {
        val cache = partialV4From111()
        val plan = TmdbServiceLookup.planFetch(cache, "abcd1234", cache.fetchedAtMs + 1000)
        assertTrue(plan.queries.isNotEmpty())
        assertEquals(originalSeven.toSet(), plan.completedServices)
        // A missing file still plans a full recheck, but a usable cache never drops completed services.
        val missing = TmdbServiceLookup.planFetch(null, "abcd1234", cache.fetchedAtMs + 1000)
        assertEquals(TmdbServiceLookup.FetchPlan.Kind.FullRecheck, missing.kind)
        assertEquals("missing", missing.reason)
        assertTrue(missing.completedServices.isEmpty())
    }

    @Test
    fun planFullRecheckOnAgeIsResumable() {
        val now = 1_000_000_000_000L
        val done = TmdbServiceCache(
            fetchedAtMs = now,
            keyFingerprint = "abcd1234",
            entries = originalSeven.mapIndexed { i, key -> entry(i + 1, key) } + entry(100, "peacock"),
            complete = true,
            queriedServices = TmdbServiceLookup.serviceKeys().sorted(),
            completedServices = TmdbServiceLookup.serviceKeys().toList(),
            appVersion = "1.4.112"
        )
        val agedAt = now + TmdbServiceLookup.TTL_MS + 1
        val plan = TmdbServiceLookup.planFetch(done, "abcd1234", agedAt)
        assertEquals(TmdbServiceLookup.FetchPlan.Kind.FullRecheck, plan.kind)
        assertEquals("age", plan.reason)
        assertTrue(plan.fullRecheck)
        assertTrue(plan.progress.all { !it.done && it.nextPage == 1 })
        // Mid-recheck resume keeps the in-flight page.
        val mid = done.copy(
            complete = false,
            fullRecheck = true,
            completedServices = emptyList(),
            progress = TmdbServiceLookup.ALL_QUERIES.map { q ->
                if (q.service?.key == "peacock" && q.type == "movie") TmdbQueryCursor(q.cursorKey(), nextPage = 4, done = false)
                else TmdbQueryCursor(q.cursorKey(), nextPage = 1, done = false)
            }
        )
        val resume = TmdbServiceLookup.planFetch(mid, "abcd1234", agedAt)
        assertEquals(TmdbServiceLookup.FetchPlan.Kind.FullRecheck, resume.kind)
        assertEquals("incomplete", resume.reason)
        val pc = resume.progress.first { it.key.contains("|peacock") && it.key.startsWith("movie|") }
        assertEquals(4, pc.nextPage)
    }

    @Test
    fun planForceIsFullRecheck() {
        val cache = partialV4From111()
        val plan = TmdbServiceLookup.planFetch(cache, "abcd1234", cache.fetchedAtMs + 1000, force = true)
        assertEquals(TmdbServiceLookup.FetchPlan.Kind.FullRecheck, plan.kind)
        assertEquals("forced", plan.reason)
        assertTrue(plan.fullRecheck)
        assertTrue(plan.queries.isNotEmpty())
    }

    @Test
    fun fetchedServiceKeysIgnoresEarlyQueriedServicesWhenIncomplete() {
        val cache = partialV4From111()
        val fetched = TmdbServiceLookup.fetchedServiceKeys(cache)
        assertEquals(originalSeven.toSet(), fetched)
        assertFalse("peacock" in fetched)
        val missing = TmdbServiceLookup.missingServices(cache)
        assertTrue("peacock" in missing && "starz" in missing)
    }

    @Test
    fun updatingStatusLineNamesTheService() {
        val q = TmdbServiceLookup.QUERIES.first { it.service?.key == "peacock" }
        assertEquals("Updating service groups: Peacock…", TmdbServiceLookup.updatingStatus(q))
        val ratings = TmdbServiceLookup.RATING_QUERIES.first()
        assertEquals("Updating service groups: ratings…", TmdbServiceLookup.updatingStatus(ratings))
    }

    @Test
    fun fetchOnlyRequestsMissingServicesAndPersistsEachPage() {
        val requested = mutableListOf<String>()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val svc = TmdbServiceLookup.QUERIES.firstOrNull { q ->
                q.type == url.pathSegments.last() &&
                    (q.value != null && url.queryParameter(q.param ?: "") == q.value ||
                        (q.param == null && url.queryParameter("sort_by") == q.sortBy))
            }
            // Fall back: record provider id or sort.
            val tag = url.queryParameter("with_watch_providers")
                ?: url.queryParameter("with_networks")
                ?: url.queryParameter("sort_by")
                ?: "unknown"
            requested.add(tag)
            val page = (url.queryParameter("page") ?: "1")
            val body = """{"page":$page,"total_pages":2,"results":[{"id":${page}01,"title":"T$page","name":"T$page","release_date":"2020-01-01","first_air_date":"2020-01-01"}]}"""
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val lookup = TmdbServiceLookup(client, delayMs = 0L)
        val cache = partialV4From111()
        val plan = TmdbServiceLookup.planFetch(cache, "abcd1234", cache.fetchedAtMs + 1000)
        val persists = mutableListOf<Int>()
        val statuses = mutableListOf<String>()
        val result = lookup.fetch(
            apiKey = "0123456789abcdef0123456789abcdef",
            seed = cache.entries,
            progress = plan.progress,
            queries = plan.queries,
            onQueryStart = { statuses.add(it) },
            onPersist = { entries, _, complete -> persists.add(entries.size) },
        )
        assertTrue(result.complete)
        assertTrue(persists.isNotEmpty())
        // Every page is checkpointed (2 pages per query).
        assertTrue(persists.size >= plan.queries.size)
        assertTrue(statuses.any { it.startsWith("Updating service groups: Peacock") })
        assertTrue(statuses.none { "Netflix" in it })
        // Provider 8 is Netflix; it must not have been requested.
        assertFalse("8" in requested)
        assertTrue(requested.any { it.contains("386") }) // Peacock
        assertTrue(result.entries.any { "peacock" in it.services })
        assertTrue(result.entries.any { "netflix" in it.services }) // seed kept
    }

    @Test
    fun fetchResumesFromCheckpointedPage() {
        val pages = mutableListOf<Int>()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val page = (url.queryParameter("page") ?: "1").toInt()
            if (url.queryParameter("with_watch_providers")?.contains("386") == true && url.pathSegments.last() == "movie") {
                pages.add(page)
            }
            val body = """{"page":$page,"total_pages":6,"results":[{"id":${page}09,"title":"P$page","name":"P$page","release_date":"2021-02-02","first_air_date":"2021-02-02"}]}"""
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val lookup = TmdbServiceLookup(client, delayMs = 0L)
        val base = partialV4From111()
        val peacockMovie = TmdbServiceLookup.QUERIES.first { it.service?.key == "peacock" && it.type == "movie" }
        val cache = base.copy(
            completedServices = originalSeven,
            progress = base.progress.map {
                if (it.key == peacockMovie.cursorKey()) it.copy(nextPage = 4, done = false) else it
            }
        )
        val plan = TmdbServiceLookup.planFetch(cache, "abcd1234", cache.fetchedAtMs + 1000)
        lookup.fetch(
            apiKey = "0123456789abcdef0123456789abcdef",
            seed = cache.entries,
            progress = plan.progress,
            queries = plan.queries.filter { it.cursorKey() == peacockMovie.cursorKey() },
        )
        assertEquals(listOf(4, 5, 6), pages)
        assertFalse(1 in pages)
    }


}
