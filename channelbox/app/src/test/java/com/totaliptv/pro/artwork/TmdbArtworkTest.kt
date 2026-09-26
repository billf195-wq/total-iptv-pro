package com.totaliptv.pro.artwork

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.xtream.XtreamApi
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbArtworkTest {

    private val v3 = "0123456789abcdef0123456789abcdef"

    @Test
    fun rewritesTvPosterAndDetailSizesWithoutShrinking() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/abc.jpg",
            TmdbArtwork.rewrite("https://image.tmdb.org/t/p/w185/abc.jpg", ArtworkRole.POSTER)
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w500/abc.jpg",
            TmdbArtwork.rewrite("https://image.tmdb.org/t/p/w342/abc.jpg", ArtworkRole.POSTER)
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w500/abc.jpg",
            TmdbArtwork.rewrite("https://image.tmdb.org/t/p/w500/abc.jpg", ArtworkRole.POSTER)
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w780/abc.jpg",
            TmdbArtwork.rewrite("https://image.tmdb.org/t/p/w780/abc.jpg", ArtworkRole.POSTER)
        )
        assertEquals(
            "https://image.tmdb.org/t/p/original/abc.jpg",
            TmdbArtwork.rewrite("https://image.tmdb.org/t/p/original/abc.jpg", ArtworkRole.POSTER)
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w780/hero.jpg",
            TmdbArtwork.rewrite("https://image.tmdb.org/t/p/w300/hero.jpg", ArtworkRole.BACKDROP)
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w780/poster.jpg",
            TmdbArtwork.rewrite("https://image.tmdb.org/t/p/w185/poster.jpg", ArtworkRole.DETAIL)
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/hero.jpg",
            TmdbArtwork.rewrite("https://image.tmdb.org/t/p/w1280/hero.jpg", ArtworkRole.BACKDROP)
        )
        assertEquals(
            "http://panel.example/covers/1.jpg",
            TmdbArtwork.rewrite("http://panel.example/covers/1.jpg", ArtworkRole.POSTER)
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w92/logo.png",
            TmdbArtwork.rewrite("https://image.tmdb.org/t/p/w92/logo.png", ArtworkRole.LOGO)
        )
        assertNull(TmdbArtwork.rewrite("  ", ArtworkRole.POSTER))
    }

    @Test
    fun keyShapesAndBearerPrefix() {
        val token = "eyJhbGciOiJIUzI1NiJ9.payload.sig"
        assertEquals(token, TmdbAuth.normalize("Bearer $token"))
        assertEquals(token, TmdbAuth.normalize("bearer $token"))
        assertTrue(TmdbAuth.isV4ReadToken("Bearer $token"))
        assertTrue(TmdbAuth.isUsable(token))
        assertTrue(TmdbAuth.isUsable(v3))
        assertFalse(TmdbAuth.isUsable("Bearer "))
        assertFalse(TmdbAuth.isUsable("not-a-key"))
        assertFalse(TmdbAuth.isUsable(""))
        val v3Req = TmdbRequest("movie/603", v3)
        assertTrue(TmdbAuth.url(v3Req).contains("api_key=$v3"))
        assertNull(TmdbAuth.authorization(v3Req))
        val v4Req = TmdbRequest("movie/603", "Bearer $token")
        assertEquals("https://api.themoviedb.org/3/movie/603", TmdbAuth.url(v4Req))
        assertFalse(TmdbAuth.url(v4Req).contains(token))
        assertFalse(TmdbAuth.url(v4Req).contains("api_key"))
        assertEquals("Bearer $token", TmdbAuth.authorization(v4Req))
        assertEquals(v3, TmdbArtwork.resolveApiKey(pref = "", fileText = "$v3\n"))
        assertEquals("from-settings", TmdbArtwork.resolveApiKey(pref = "from-settings", fileText = v3))
        assertEquals(TmdbKeyFile.FILE_NAME, "tmdb-api-key.txt")
        assertEquals(
            "/sdcard/Android/data/com.totaliptv.pro/files/tmdb-api-key.txt",
            TmdbKeyFile.ADB_PATH
        )
    }

    @Test
    fun cleansTitlesAndPicksAnExactYearMatch() {
        val matrix = TmdbTitle.clean("EN | The Matrix (1999) 1080p BluRay x264")
        assertEquals("The Matrix", matrix.title)
        assertEquals(1999, matrix.year)
        assertEquals("the matrix", matrix.normalized)
        val wrapped = TmdbTitle.clean("|FR| Amelie [2001] WEBRip")
        assertEquals("Amelie", wrapped.title)
        assertEquals(2001, wrapped.year)
        val dotted = TmdbTitle.clean("The.Matrix.1999.1080p.BluRay.x264")
        assertEquals("The Matrix", dotted.title)
        assertEquals(1999, dotted.year)

        val hits = listOf(
            hit("1", "The Matrix", 1998, popularity = 90.0),
            hit("2", "The Matrix", 1999, popularity = 12.0),
            hit("3", "The Matrix Reloaded", 1999, popularity = 80.0),
            hit("4", "The Matrix", 1999, popularity = 40.0)
        )
        assertEquals("4", TmdbTitle.pick(hits, matrix)?.id)
        val noYear = TmdbTitle.clean("The Matrix")
        assertNull(noYear.year)
        assertEquals("1", TmdbTitle.pick(hits, noYear)?.id)
        assertNull(TmdbTitle.pick(hits, TmdbTitle.clean("Something Else (1999)")))
    }

    @Test
    fun scoresHideTensAndKeepLowVotesOffTheTop() {
        assertEquals(0.0, TmdbRatings.providerScore("10.0"))
        assertEquals(0.0, TmdbRatings.providerScore("10"))
        assertEquals(8.2, TmdbRatings.providerScore("8.2"))
        val strong = TmdbRating(8.4, 100, found = true, fetchedAtMs = 1)
        val thin = TmdbRating(9.1, 5, found = true, fetchedAtMs = 1)
        assertEquals(8.4, TmdbRatings.displayScore(0.0, strong, enabled = true))
        assertEquals(8.4, TmdbRatings.rankScore(0.0, strong, enabled = true))
        assertEquals(9.1, TmdbRatings.displayScore(0.0, thin, enabled = true))
        assertEquals(0.0, TmdbRatings.rankScore(8.0, thin, enabled = true))
        assertEquals(8.2, TmdbRatings.displayScore(8.2, strong, enabled = false))
        assertEquals("8.4", TmdbRatings.formatScore(8.4))
        assertNull(TmdbRatings.formatScore(0.0))
        val freshMiss = TmdbRating(0.0, 0, found = false, fetchedAtMs = 1_000)
        assertTrue(TmdbRatings.isFresh(freshMiss, 1_000 + TmdbRatings.MISS_AGE_MS - 1))
        assertFalse(TmdbRatings.isFresh(freshMiss, 1_000 + TmdbRatings.MISS_AGE_MS))
        assertTrue(TmdbRatings.isFresh(strong.copy(fetchedAtMs = 1_000), 1_000 + TmdbRatings.MAX_AGE_MS - 1))
        assertFalse(TmdbRatings.isFresh(strong.copy(fetchedAtMs = 1_000), 1_000 + TmdbRatings.MAX_AGE_MS))
        assertEquals("tmdb ratings resolved=3 missed=2 ms=840", TmdbRatings.batchLine(3, 2, 840))
    }

    @Test
    fun blankKeyDoesNotCallTheApi() {
        val engine = engine()
        var calls = 0
        engine.fetch = {
            calls += 1
            error("should not fetch")
        }
        engine.apiKey = { "" }
        engine.enqueue(listOf(vod("matrix", "10.0", "The Matrix (1999)")), front = true)
        engine.drainForTests()
        engine.apiKey = { "not-a-real-key" }
        engine.enqueue(listOf(vod("matrix", "10.0", "The Matrix (1999)")), front = true)
        engine.drainForTests()
        assertEquals(0, calls)
        assertEquals(0.0, engine.displayScore(vod("matrix", "10.0", "The Matrix (1999)")))
        engine.ratingsEnabled = { false }
        engine.apiKey = { v3 }
        engine.enqueue(listOf(vod("matrix", "8.2", "The Matrix (1999)", tmdbId = "603")), front = true)
        engine.drainForTests()
        assertEquals(0, calls)
    }

    @Test
    fun searchResolvesAConfidentMatchAndCachesIt() {
        val engine = engine()
        engine.apiKey = { v3 }
        val logs = mutableListOf<String>()
        engine.log = { logs += it }
        val paths = mutableListOf<String>()
        engine.fetch = { req ->
            paths += req.pathAndQuery
            assertFalse(TmdbAuth.url(req).contains("api_key=") && TmdbAuth.url(req).contains("eyJ"))
            assertTrue(TmdbAuth.url(req).contains("api_key=$v3"))
            if (req.pathAndQuery.startsWith("search/movie")) {
                """{"results":[
                    {"id":1,"title":"The Matrix","release_date":"1998-01-01","popularity":90,"vote_average":7.0,"vote_count":10},
                    {"id":603,"title":"The Matrix","release_date":"1999-03-31","popularity":40,"vote_average":8.7,"vote_count":20000}
                ]}"""
            } else null
        }
        val item = vod("matrix", "10.0", "EN | The Matrix (1999) 1080p")
        engine.enqueue(listOf(item), front = true)
        engine.drainForTests()
        assertEquals(8.7, engine.displayScore(item))
        assertEquals(8.7, engine.rankScore(item))
        assertTrue(paths.single().startsWith("search/movie"))
        assertTrue(paths.single().contains("year=1999"))
        assertEquals(1, logs.size)
        assertTrue(logs.single().startsWith("tmdb ratings resolved=1 missed=0 ms="))
        assertFalse(logs.single().contains(v3))
        val callsAfter = paths.size
        engine.enqueue(listOf(item), front = true)
        engine.drainForTests()
        assertEquals(callsAfter, paths.size)
    }

    @Test
    fun missesExpireInADayAndHitsLastSeven() {
        val engine = engine()
        engine.apiKey = { v3 }
        var calls = 0
        engine.fetch = {
            calls += 1
            """{"results":[]}"""
        }
        val item = vod("nope", "6.0", "Unknown Title (1999)")
        engine.enqueue(listOf(item), front = true)
        engine.drainForTests()
        assertEquals(2, calls)
        engine.enqueue(listOf(item), front = true)
        engine.drainForTests()
        assertEquals(2, calls)
        engine.nowMs = { 1_000_000L + TmdbRatings.MISS_AGE_MS }
        engine.enqueue(listOf(item), front = true)
        engine.drainForTests()
        assertEquals(4, calls)

        calls = 0
        engine.nowMs = { 5_000_000L }
        engine.fetch = {
            calls += 1
            """{"vote_average":8.4,"vote_count":100}"""
        }
        val known = vod("known", "10.0", "The Matrix", tmdbId = "603")
        engine.enqueue(listOf(known), front = true)
        engine.drainForTests()
        assertEquals(1, calls)
        assertEquals(8.4, engine.displayScore(known))
        val file = engine.storeFile
        assertTrue(file.readText().contains("8.4"))
        assertFalse(file.readText().contains(v3))
        val reloaded = TmdbRatingEngine(storeFile = file, autoDrain = false, pauseMs = 0, nowMs = { 5_000_000L })
        reloaded.apiKey = { v3 }
        reloaded.fetch = { error("disk hit") }
        assertEquals(8.4, reloaded.displayScore(known))
        reloaded.nowMs = { 5_000_000L + TmdbRatings.MAX_AGE_MS }
        var again = 0
        reloaded.fetch = {
            again += 1
            """{"vote_average":8.1,"vote_count":100}"""
        }
        reloaded.enqueue(listOf(known), front = true)
        reloaded.drainForTests()
        assertEquals(1, again)
    }

    @Test
    fun visibleItemsJumpTheQueueAndBatchesStaySmall() {
        val engine = engine()
        engine.apiKey = { v3 }
        val items = (1..7).map { vod("m$it", "6.0", "Title $it", tmdbId = "$it") }
        engine.enqueue(items, front = false)
        engine.enqueue(listOf(items.last()), front = true)
        assertEquals("movie:7", engine.queuedKeysForTests().first())
        val logs = mutableListOf<String>()
        engine.log = { logs += it }
        engine.fetch = { """{"vote_average":7.5,"vote_count":30}""" }
        engine.drainForTests()
        assertEquals(2, logs.size)
        assertTrue(logs[0].contains("resolved=5"))
        assertTrue(logs[1].contains("resolved=2"))
        assertTrue(logs.all { !it.contains(v3) })
    }

    @Test
    fun lowVotesCannotLeadAndAuthFailureIsNotCached() {
        val engine = engine()
        engine.apiKey = { v3 }
        engine.fetch = { req ->
            when {
                req.pathAndQuery.startsWith("movie/22") -> """{"vote_average":9.9,"vote_count":5}"""
                req.pathAndQuery.startsWith("movie/33") -> """{"vote_average":8.4,"vote_count":100}"""
                else -> """{"status_code":7,"status_message":"Invalid API key"}"""
            }
        }
        val thin = vod("thin", "10.0", "Thin", tmdbId = "22")
        val solid = vod("solid", "6.0", "Solid", tmdbId = "33")
        engine.enqueue(listOf(thin, solid), front = true)
        engine.drainForTests()
        assertEquals(9.9, engine.displayScore(thin))
        assertEquals(0.0, engine.rankScore(thin))
        assertEquals(8.4, engine.rankScore(solid))

        var calls = 0
        engine.fetch = {
            calls += 1
            """{"status_code":7}"""
        }
        val bad = vod("bad", "8.0", "Bad", tmdbId = "9")
        engine.enqueue(listOf(bad), front = true)
        engine.drainForTests()
        val first = calls
        assertTrue(first >= 2)
        engine.enqueue(listOf(bad), front = true)
        engine.drainForTests()
        assertTrue(calls > first)
    }

    @Test
    fun v4TokenStaysOutOfTheRatingUrl() {
        val token = "eyJhbGciOiJIUzI1NiJ9.payload.sig"
        val engine = engine()
        engine.apiKey = { "Bearer $token" }
        engine.fetch = { req ->
            assertEquals("https://api.themoviedb.org/3/movie/7", TmdbAuth.url(req))
            assertEquals("Bearer $token", TmdbAuth.authorization(req))
            """{"vote_average":8.1,"vote_count":30}"""
        }
        val item = vod("a", "10.0", "A", tmdbId = "7")
        engine.enqueue(listOf(item), front = true)
        engine.drainForTests()
        assertEquals(8.1, engine.displayScore(item))
    }

    @Test
    fun xtreamIdAndSettingsWiring() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals("603", XtreamApi.flexibleId(json.parseToJsonElement("603")))
        assertEquals("603", XtreamApi.flexibleId(json.parseToJsonElement("\"603\"")))
        assertNull(XtreamApi.flexibleId(json.parseToJsonElement("0")))
        assertNull(XtreamApi.flexibleId(json.parseToJsonElement("null")))
        val settings = File("src/tv/java/com/totaliptv/pro/ui/settings/SettingsScreen.kt").readText()
        val desktop = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
        val phone = File("src/phone/java/com/totaliptv/pro/ui/browse/PhoneBrowseScreen.kt").readText()
        for (source in listOf(settings, desktop)) {
            assertTrue(source.contains("Sharp posters (HD artwork)"))
            assertTrue(source.contains("TMDB ratings"))
            assertTrue(source.contains("TMDB API key"))
            assertTrue(source.contains("tmdb-api-key.txt"))
        }
        assertFalse(phone.contains("Game Day"))
        assertFalse(phone.contains("GameDayPicker"))
        val phoneSettings = File("src/phone/java/com/totaliptv/pro/ui/settings/PhoneSettingsScreen.kt").readText()
        assertFalse(phoneSettings.contains("TMDB API key"))
        assertFalse(phoneSettings.contains("Sharp posters"))
    }

    private fun engine(): TmdbRatingEngine {
        val dir = File.createTempFile("tmdb-ratings", ".dir")
        dir.delete()
        dir.mkdirs()
        return TmdbRatingEngine(
            storeFile = File(dir, "tmdb-ratings.json"),
            autoDrain = false,
            pauseMs = 0,
            nowMs = { 1_000_000L },
            apiKey = { "" }
        )
    }

    private fun hit(id: String, title: String, year: Int, popularity: Double) = TmdbSearchHit(
        id = id,
        title = title,
        year = year,
        popularity = popularity,
        average = 7.0,
        votes = 10
    )

    private fun vod(id: String, rating: String, name: String, tmdbId: String? = null) = MediaItem(
        id = id,
        name = name,
        streamUrl = "http://example/$id",
        categoryId = "c",
        kind = ContentKind.VOD,
        rating = rating,
        tmdbId = tmdbId
    )
}
