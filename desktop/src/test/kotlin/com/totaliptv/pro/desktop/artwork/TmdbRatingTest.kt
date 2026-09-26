package com.totaliptv.pro.desktop.artwork

import com.totaliptv.pro.desktop.data.Catalog
import com.totaliptv.pro.desktop.data.ContentKind
import com.totaliptv.pro.desktop.data.MediaItem
import com.totaliptv.pro.desktop.player.PlaybackDebugLog
import com.totaliptv.pro.desktop.ui.pickTopRatedMovies
import com.totaliptv.pro.desktop.ui.pickTopRatedSeries
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TmdbRatingTest {
    @BeforeTest
    fun reset() {
        ArtworkSettings.tmdbApiKey = ""
        ArtworkSettings.tmdbRatings = true
        TmdbRatingStore.fileOverride = Files.createTempDirectory("tmdb-ratings").resolve("ratings.json")
        TmdbRatingStore.resetForTests()
        TmdbRatingStore.fetch = { null }
        TmdbRatingStore.nowMs = { 1_000_000L }
    }

    @AfterTest
    fun cleanup() {
        TmdbRatingStore.resetForTests()
        TmdbRatingStore.fileOverride = null
        TmdbRatingStore.pauseMs = 0
        TmdbRatingStore.autoDrain = true
        TmdbRatingStore.parallel = true
        TmdbRatingStore.maxInFlight = TmdbRatingStore.MAX_IN_FLIGHT
        TmdbRatingStore.startGapMs = TmdbRatingStore.START_GAP_MS
        TmdbRatingStore.log = { PlaybackDebugLog.note(it) }
        TmdbRatingStore.fetch = { TmdbClient.get(it) }
        TmdbRatingStore.nowMs = { System.currentTimeMillis() }
        ArtworkSettings.tmdbApiKey = ""
        ArtworkSettings.tmdbRatings = true
    }

    @Test
    fun providerTensAreHiddenAndRealScoresStay() {
        assertEquals(0.0, TmdbRatings.providerScore("10.0", null))
        assertEquals(0.0, TmdbRatings.providerScore("10", null))
        assertEquals(0.0, TmdbRatings.providerScore(null, 5.0))
        assertEquals(8.2, TmdbRatings.providerScore("8.2", null))
        assertEquals(8.2, TmdbRatings.providerScore(null, 4.1))
        assertEquals(9.9, TmdbRatings.providerScore("9.9", null))
    }

    @Test
    fun tmdbAverageReplacesProviderAndLowVotesCannotLead() {
        val strong = TmdbRating(8.4, 100, found = true, fetchedAtMs = 1)
        val thin = TmdbRating(9.1, 5, found = true, fetchedAtMs = 1)
        assertEquals(8.4, TmdbRatings.displayScore(0.0, strong, enabled = true))
        assertEquals(8.4, TmdbRatings.rankScore(0.0, strong, enabled = true))
        assertEquals(9.1, TmdbRatings.displayScore(10.0, thin, enabled = true))
        assertEquals(0.0, TmdbRatings.rankScore(8.0, thin, enabled = true))
        assertEquals(8.2, TmdbRatings.displayScore(8.2, strong, enabled = false))
        assertEquals(8.2, TmdbRatings.rankScore(8.2, strong, enabled = false))
        assertTrue(TmdbRatings.isFresh(1_000, 1_000 + TmdbRatings.MAX_AGE_MS - 1))
        assertFalse(TmdbRatings.isFresh(1_000, 1_000 + TmdbRatings.MAX_AGE_MS))
    }

    @Test
    fun cachedTmdbRatingBeatsBogusProviderTen() {
        enableKey()
        TmdbRatingStore.fetch = { req ->
            when {
                req.pathAndQuery.startsWith("movie/22") -> """{"vote_average":9.9,"vote_count":5}"""
                req.pathAndQuery.startsWith("movie/33") -> """{"vote_average":8.4,"vote_count":100}"""
                else -> """{"status_code":34}"""
            }
        }
        val thin = vod("thin", "10.0", "22")
        val solid = vod("solid", "6.0", "33")
        TmdbRatingStore.enqueue(listOf(thin, solid), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(9.9, TmdbRatingStore.displayScore(thin))
        assertEquals(0.0, TmdbRatingStore.rankScore(thin))
        assertEquals(8.4, TmdbRatingStore.displayScore(solid))
        assertEquals(8.4, TmdbRatingStore.rankScore(solid))
        val row = pickTopRatedMovies(Catalog(vodItems = listOf(thin, solid))) { TmdbRatingStore.rankScore(it) }
        assertEquals(listOf("solid"), row.items.map { it.id })
    }

    @Test
    fun ratingsRefreshAfterSevenDaysAndRoundTripOnDisk() {
        enableKey()
        var calls = 0
        TmdbRatingStore.fetch = {
            calls += 1
            """{"vote_average":8.4,"vote_count":100}"""
        }
        val item = vod("matrix", "10.0", "603")
        TmdbRatingStore.enqueue(listOf(item), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(1, calls)
        TmdbRatingStore.enqueue(listOf(item), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(1, calls)
        val file = TmdbRatingStore.fileOverride!!
        assertTrue(Files.readString(file).contains("8.4"))
        TmdbRatingStore.resetForTests()
        assertEquals(8.4, TmdbRatingStore.displayScore(item))
        assertEquals(1, calls)
        TmdbRatingStore.nowMs = { 1_000_000L + TmdbRatings.MAX_AGE_MS }
        TmdbRatingStore.enqueue(listOf(item), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(2, calls)
    }

    @Test
    fun noKeyOrDisabledToggleDoesNotFetch() {
        var calls = 0
        TmdbRatingStore.fetch = { calls += 1; null }
        TmdbRatingStore.enqueue(listOf(vod("a", "10.0", "1")), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(0, calls)
        assertEquals(0.0, TmdbRatingStore.displayScore(vod("a", "10.0", "1")))

        enableKey()
        ArtworkSettings.tmdbRatings = false
        TmdbRatingStore.enqueue(listOf(vod("a", "8.2", "1")), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(0, calls)
        assertTrue(TmdbRatingStore.queuedIdsForTests().isEmpty())
    }

    @Test
    fun authFailureIsNotCachedAndARealMissIs() {
        enableKey()
        var calls = 0
        TmdbRatingStore.fetch = {
            calls += 1
            """{"status_code":7,"status_message":"Invalid API key"}"""
        }
        val item = vod("a", "8.0", "9")
        TmdbRatingStore.enqueue(listOf(item), front = true)
        TmdbRatingStore.drainForTests()
        val first = calls
        assertEquals(2, first)
        TmdbRatingStore.enqueue(listOf(item), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(4, calls)

        calls = 0
        TmdbRatingStore.fetch = {
            calls += 1
            """{"status_code":34,"status_message":"The resource you requested could not be found."}"""
        }
        val show = series("show", "8.0", "55")
        val paths = mutableListOf<String>()
        TmdbRatingStore.fetch = { req ->
            calls += 1
            paths += req.pathAndQuery
            if (req.pathAndQuery.startsWith("tv/")) {
                """{"status_code":34}"""
            } else {
                """{"vote_average":8.0,"vote_count":40}"""
            }
        }
        TmdbRatingStore.enqueue(listOf(show), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(listOf("tv/55", "movie/55"), paths)
        assertEquals(8.0, TmdbRatingStore.displayScore(show))
        val afterHit = calls
        TmdbRatingStore.enqueue(listOf(show), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(afterHit, calls)
    }

    @Test
    fun visibleTitlesJumpAheadOfTheBackfill() {
        enableKey()
        val items = (1..4).map { vod("m$it", "6.0", "$it") }
        TmdbRatingStore.enqueue(items, front = false)
        TmdbRatingStore.enqueue(listOf(items.last()), front = true)
        assertEquals(listOf("4", "1", "2", "3"), TmdbRatingStore.queuedIdsForTests())
    }

    @Test
    fun v4TokenIsSentAsBearerForRatings() {
        ArtworkSettings.tmdbRatings = true
        ArtworkSettings.tmdbApiKey = "eyJhbGciOiJIUzI1NiJ9.payload.sig"
        TmdbRatingStore.fetch = { req ->
            assertFalse(TmdbAuth.url(req).contains("api_key"))
            assertEquals("Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig", TmdbAuth.authorization(req))
            """{"vote_average":8.1,"vote_count":30}"""
        }
        val item = vod("a", "10.0", "7")
        TmdbRatingStore.enqueue(listOf(item), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(8.1, TmdbRatingStore.displayScore(item))
    }

    @Test
    fun topRatedHidesBogusTensAndUsesTheRankFunction() {
        val items = listOf(
            vod("ten", "10.0"),
            vod("a", "6.1"),
            vod("b", "7.2"),
            vod("c", "8.3"),
            vod("d", "5.4"),
            vod("e", "4.5"),
            vod("f", "9.0")
        )
        val row = pickTopRatedMovies(Catalog(vodItems = items))
        assertEquals("Top rated new American movies", row.title)
        assertEquals("f", row.items.first().id)
        assertFalse(row.items.any { it.id == "ten" })

        val ranked = pickTopRatedMovies(Catalog(vodItems = items)) { item ->
            when (item.id) {
                "f" -> 0.0
                "ten" -> 8.4
                else -> TmdbRatings.providerScore(item)
            }
        }
        assertEquals("ten", ranked.items.first().id)
        assertFalse(ranked.items.any { it.id == "f" })

        val shows = listOf(series("hit", "8.1"), series("pad", "10.0"))
        val seriesRow = pickTopRatedSeries(Catalog(seriesItems = shows))
        assertEquals(listOf("hit"), seriesRow.items.map { it.id })
    }

    @Test
    fun titleSearchFillsGridRatingsWhenTheProviderHasNoTmdbId() {
        enableKey()
        val lines = mutableListOf<String>()
        TmdbRatingStore.log = { lines += it }
        val paths = mutableListOf<String>()
        TmdbRatingStore.fetch = { req ->
            paths += req.pathAndQuery
            when {
                req.pathAndQuery.startsWith("search/movie") && req.pathAndQuery.contains("The+Matrix") ->
                    """{"results":[
                        {"id":1,"title":"The Matrix Reloaded","release_date":"2003-05-15","popularity":200,"vote_average":7.0,"vote_count":9000},
                        {"id":603,"title":"The Matrix","original_title":"The Matrix","release_date":"1999-03-31","popularity":40,"vote_average":8.7,"vote_count":20000}
                    ]}"""
                req.pathAndQuery.startsWith("search/tv") ->
                    """{"results":[
                        {"id":1396,"name":"Breaking Bad","original_name":"Breaking Bad","first_air_date":"2008-01-20","popularity":80,"vote_average":8.9,"vote_count":15000}
                    ]}"""
                else -> """{"results":[]}"""
            }
        }
        val matrix = titled("matrix", "EN - The Matrix (1999) 1080p BluRay", ContentKind.VOD, rating = "10.0")
        val show = titled("bb", "|EN| Breaking Bad (2008) WEB-DL", ContentKind.SERIES, rating = "10.0", year = null)
        TmdbRatingStore.enqueue(listOf(matrix, show), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(
            listOf(
                "search/movie?query=The+Matrix&year=1999",
                "search/tv?query=Breaking+Bad&first_air_date_year=2008"
            ),
            paths
        )
        assertEquals(8.7, TmdbRatingStore.displayScore(matrix))
        assertEquals(8.7, TmdbRatingStore.rankScore(matrix))
        assertEquals(8.9, TmdbRatingStore.displayScore(show))
        assertEquals(8.9, TmdbRatingStore.rankScore(show))
        val line = lines.single()
        assertTrue(Regex("""ratings resolved=2 missed=0 ms=\d+""").matches(line))
        assertFalse(line.contains("secret"))
        assertFalse(line.contains("api_key"))
        assertFalse(Files.readString(TmdbRatingStore.fileOverride!!).contains("secret"))
        val again = paths.size
        TmdbRatingStore.enqueue(listOf(matrix, show), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(again, paths.size)
        val row = pickTopRatedMovies(Catalog(vodItems = listOf(matrix))) { TmdbRatingStore.rankScore(it) }
        assertEquals("matrix", row.items.single().id)
    }

    @Test
    fun unsureTitleMatchesAreSkippedAndRetriedAfterADay() {
        enableKey()
        var calls = 0
        TmdbRatingStore.fetch = {
            calls += 1
            """{"results":[{"id":1,"title":"The Matrix Reloaded","release_date":"1999-01-01","popularity":500,"vote_average":9.9,"vote_count":100}]}"""
        }
        val item = titled("m", "The Matrix (1999)", ContentKind.VOD, rating = "8.2")
        TmdbRatingStore.enqueue(listOf(item), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(1, calls)
        assertEquals(8.2, TmdbRatingStore.displayScore(item))
        assertEquals(8.2, TmdbRatingStore.rankScore(item))
        TmdbRatingStore.enqueue(listOf(item), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(1, calls)
        TmdbRatingStore.nowMs = { 1_000_000L + TmdbRatings.MISS_AGE_MS }
        TmdbRatingStore.enqueue(listOf(item), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(2, calls)
    }

    @Test
    fun lowVoteSearchHitShowsOnTheTileButDoesNotLeadTopRated() {
        enableKey()
        TmdbRatingStore.fetch = { req ->
            if (req.pathAndQuery.contains("Dune")) {
                """{"results":[{"id":438631,"title":"Dune","release_date":"2021-09-15","popularity":10,"vote_average":9.4,"vote_count":5}]}"""
            } else {
                """{"results":[{"id":603,"title":"The Matrix","release_date":"1999-03-31","popularity":20,"vote_average":8.4,"vote_count":100}]}"""
            }
        }
        val dune = titled("dune", "Dune (2021)", ContentKind.VOD, rating = "10.0")
        val matrix = titled("matrix", "The Matrix (1999)", ContentKind.VOD, rating = "6.0")
        TmdbRatingStore.enqueue(listOf(dune, matrix), front = true)
        TmdbRatingStore.drainForTests()
        assertEquals(9.4, TmdbRatingStore.displayScore(dune))
        assertEquals(0.0, TmdbRatingStore.rankScore(dune))
        assertEquals(8.4, TmdbRatingStore.rankScore(matrix))
        val row = pickTopRatedMovies(Catalog(vodItems = listOf(dune, matrix))) { TmdbRatingStore.rankScore(it) }
        assertEquals(listOf("matrix"), row.items.map { it.id })
    }

    @Test
    fun titleCleanupAndMatchPreferExactTitleThenPopularity() {
        val matrix = TmdbTitle.query(
            titled("1", "EN - The Matrix (1999) 1080p BluRay", ContentKind.VOD, year = null)
        )
        assertEquals("The Matrix", matrix?.title)
        assertEquals(1999, matrix?.year)
        val dune = TmdbTitle.query(titled("2", "|FR| Dune Part Two (2024) WEB-DL", ContentKind.VOD, year = null))
        assertEquals("Dune Part Two", dune?.title)
        assertEquals(2024, dune?.year)
        val itMovie = TmdbTitle.query(titled("3", "It (2017)", ContentKind.VOD, year = null))
        assertEquals("It", itMovie?.title)
        val kept = TmdbTitle.query(titled("4", "Blade Runner 2049", ContentKind.VOD, year = 2017))
        assertEquals("Blade Runner 2049", kept?.title)
        assertEquals(2017, kept?.year)
        val inception = TmdbTitle.query(titled("5", "DE: Inception 2010", ContentKind.VOD, year = null))
        assertEquals("Inception", inception?.title)
        assertEquals(2010, inception?.year)
        assertEquals(null, TmdbTitle.query(titled("6", "Some Movie", ContentKind.VOD, year = null)))

        val body = """{"results":[
            {"id":1,"title":"The Matrix Reloaded","release_date":"1999-05-15","popularity":500,"vote_average":9.9,"vote_count":100},
            {"id":10,"title":"The Matrix","release_date":"1999-03-31","popularity":15,"vote_average":8.0,"vote_count":100},
            {"id":11,"title":"The Matrix","original_title":"The Matrix","release_date":"1999-03-31","popularity":60,"vote_average":8.7,"vote_count":200}
        ]}"""
        val hit = TmdbTitle.bestMatch(body, TmdbTitle.Query("The Matrix", 1999, ContentKind.VOD))
        assertEquals("11", hit?.tmdbId)
        assertEquals(8.7, hit?.average)
        assertEquals("1", TmdbTitle.bestMatch(
            """{"results":[{"id":1,"title":"The Matrix Reloaded","release_date":"2003-05-15","popularity":10,"vote_average":7.0,"vote_count":20}]}""",
            TmdbTitle.Query("The Matrix Reloaded", 2003, ContentKind.VOD)
        )?.tmdbId)
        assertEquals(null, TmdbTitle.bestMatch(body, TmdbTitle.Query("The Matrix", 2005, ContentKind.VOD)))
        assertTrue(TmdbRatingStore.MAX_IN_FLIGHT in 4..6)
        assertTrue(1000.0 / TmdbRatingStore.START_GAP_MS < 10.0)
        val logged = TmdbRatings.batchLine(4, 1, 380)
        assertEquals("ratings resolved=4 missed=1 ms=380", logged)
        assertFalse(logged.contains("secret"))
    }

    @Test
    fun settingsAndHomeWireTheKeyAndTheRank() {
        val settings = source("src/main/kotlin/com/totaliptv/pro/desktop/ui/SettingsScreen.kt")
        assertTrue(settings.contains("TMDB API key"))
        assertTrue(settings.contains("TMDB ratings"))
        assertTrue(settings.contains("tmdbApiKey"))
        assertTrue(settings.contains("tmdbRatings"))
        val home = source("src/main/kotlin/com/totaliptv/pro/desktop/ui/HomeScreen.kt")
        assertTrue(home.contains("TmdbRatingStore.rankScore"))
        assertTrue(home.contains("TmdbRatingStore.enqueue"))
        val browse = source("src/main/kotlin/com/totaliptv/pro/desktop/ui/BrowseScreen.kt")
        assertTrue(browse.contains("rememberRatingScore"))
        assertTrue(browse.contains("rememberDetailRating"))
    }

    private fun enableKey() {
        ArtworkSettings.tmdbRatings = true
        ArtworkSettings.tmdbApiKey = "secret"
    }

    private fun titled(
        id: String,
        name: String,
        kind: ContentKind,
        rating: String? = null,
        year: Int? = 2024,
        tmdbId: String? = null
    ) = MediaItem(
        id = id,
        name = name,
        streamUrl = "",
        categoryId = null,
        kind = kind,
        rating = rating,
        country = "United States",
        year = year,
        tmdbId = tmdbId,
        playable = kind != ContentKind.SERIES
    )

    private fun vod(id: String, rating: String?, tmdbId: String? = null) = MediaItem(
        id = id,
        name = "Title $id",
        streamUrl = "http://example/$id",
        categoryId = "c",
        kind = ContentKind.VOD,
        rating = rating,
        country = "United States",
        year = 2024,
        tmdbId = tmdbId
    )

    private fun series(id: String, rating: String?, tmdbId: String? = null) = MediaItem(
        id = id,
        name = "Show $id",
        streamUrl = "",
        categoryId = "c",
        kind = ContentKind.SERIES,
        rating = rating,
        country = "United States",
        year = 2024,
        tmdbId = tmdbId,
        playable = false
    )

    private fun source(relative: String): String {
        val file = listOf(java.nio.file.Path.of(relative), java.nio.file.Path.of("desktop").resolve(relative))
            .firstOrNull { Files.isRegularFile(it) }
            ?: error("missing $relative from ${java.nio.file.Path.of("").toAbsolutePath()}")
        return Files.readString(file)
    }
}
