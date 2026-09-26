package com.totaliptv.pro.desktop.artwork

import com.totaliptv.pro.desktop.data.ContentKind
import com.totaliptv.pro.desktop.data.SavedPrefs
import kotlinx.serialization.json.Json
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.Surface
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbArtworkTest {

    @Test
    fun rewritesSmallTmdbPostersAndBackdropsWithoutShrinkingLargerOnes() {
        assertEquals(
            "https://image.tmdb.org/t/p/w780/abc.jpg",
            TmdbArtwork.rewrite("https://image.tmdb.org/t/p/w185/abc.jpg", ArtworkRole.POSTER)
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w780/abc.jpg",
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
            "https://image.tmdb.org/t/p/w1280/hero.jpg",
            TmdbArtwork.rewrite("https://image.tmdb.org/t/p/w300/hero.jpg", ArtworkRole.BACKDROP)
        )
        assertEquals(
            "https://image.tmdb.org/t/p/original/hero.jpg",
            TmdbArtwork.rewrite("https://image.tmdb.org/t/p/original/hero.jpg", ArtworkRole.BACKDROP)
        )
        assertEquals(
            "http://panel.example/covers/1.jpg",
            TmdbArtwork.rewrite("http://panel.example/covers/1.jpg", ArtworkRole.POSTER)
        )
        assertNull(TmdbArtwork.rewrite("  ", ArtworkRole.POSTER))
    }

    @Test
    fun smallDecodeIsFlaggedAndNativeSizeIsNotUpscaled() {
        assertTrue(TmdbArtwork.isTooSmall(185, 278, 780))
        assertFalse(TmdbArtwork.isTooSmall(780, 1170, 780))
        assertEquals(185 to 278, ArtworkDecode.outputSize(185, 278, 780))
        assertEquals(520 to 780, ArtworkDecode.outputSize(2000, 3000, 780))
    }

    @Test
    fun largeReductionsUseMipmapsAndModerateOnesUseCubic() {
        assertTrue(ArtworkDecode.samplingFor(2000, 400) is FilterMipmap)
        assertEquals(org.jetbrains.skia.SamplingMode.CATMULL_ROM, ArtworkDecode.samplingFor(1000, 780))
    }

    @Test
    fun noApiKeyDoesNotCallTheNetwork() {
        var calls = 0
        val result = TmdbArtwork.lookup(
            apiKey = "  ",
            tmdbId = "603",
            title = "The Matrix",
            year = 1999,
            kind = ContentKind.VOD,
            role = ArtworkRole.POSTER,
            fetch = {
                calls += 1
                error("should not fetch")
            }
        )
        assertNull(result)
        assertEquals(0, calls)
        assertEquals("", TmdbArtwork.resolveApiKey(pref = "", env = "", fileText = null))
    }

    @Test
    fun idLookupUsesTmdbAndSearchIsTheFallback() {
        val movie = """{"poster_path":"/matrix.jpg","backdrop_path":"/matrix-bg.jpg"}"""
        var urls = mutableListOf<String>()
        val fromId = TmdbArtwork.lookup(
            apiKey = "secret",
            tmdbId = "603",
            title = "The Matrix",
            year = 1999,
            kind = ContentKind.VOD,
            role = ArtworkRole.POSTER,
            fetch = { url ->
                urls += url
                if (url.contains("/movie/603")) movie else null
            }
        )
        assertEquals("https://image.tmdb.org/t/p/w780/matrix.jpg", fromId)
        assertTrue(urls.single().startsWith("https://api.themoviedb.org/3/movie/603?"))
        assertTrue(urls.single().contains("api_key=secret"))

        urls = mutableListOf()
        val fromSearch = TmdbArtwork.lookup(
            apiKey = "secret",
            tmdbId = null,
            title = "The Matrix",
            year = 1999,
            kind = ContentKind.VOD,
            role = ArtworkRole.BACKDROP,
            fetch = { url ->
                urls += url
                if (url.contains("search/movie")) {
                    """{"results":[{"poster_path":"/p.jpg","backdrop_path":"/b.jpg"}]}"""
                } else null
            }
        )
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", fromSearch)
        assertTrue(urls.single().contains("query=The+Matrix") || urls.single().contains("query=The%20Matrix"))
        assertTrue(urls.single().contains("year=1999"))
    }

    @Test
    fun sharpPostersDefaultOnAndOldPrefsStillLoad() {
        assertTrue(SavedPrefs().sharpPosters)
        val loaded = Json { ignoreUnknownKeys = true }.decodeFromString<SavedPrefs>("""{"themeMode":"dark"}""")
        assertTrue(loaded.sharpPosters)
    }

    @Test
    fun diskCacheRoundTripsAndEvicts() {
        val dir = Files.createTempDirectory("artwork-cache-test")
        val cache = ArtworkDiskCache(dir, maxBytes = 20)
        cache.write("https://cdn.example/a.jpg", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        assertEquals(10, cache.read("https://cdn.example/a.jpg")?.size)
        Thread.sleep(20)
        cache.write("https://cdn.example/b.jpg", byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9))
        assertNull(cache.read("https://cdn.example/a.jpg"))
        assertEquals(12, cache.read("https://cdn.example/b.jpg")?.size)
    }

    @Test
    fun highQualityScaleDoesNotEnlarge() {
        val surface = Surface.makeRasterN32Premul(40, 60)
        val image = surface.makeImageSnapshot()
        val scaled = ArtworkDecode.scale(image, 780, highQuality = true)
        assertEquals(40, scaled.width)
        assertEquals(60, scaled.height)
    }

    @Test
    fun posterPageLogLineReportsFirstLoad() {
        val burst = PosterLoadLog.Burst(
            page = "Movies",
            firstMs = 1_000,
            lastMs = 1_800,
            count = 12,
            network = 10,
            disk = 2,
            memory = 0,
            fetchMs = 2400,
            widthSum = 780 * 12,
            heightSum = 1170 * 12,
            sized = 12
        )
        val line = PosterLoadLog.format(burst)
        assertTrue(line.contains("page=Movies"))
        assertTrue(line.contains("firstLoad=true"))
        assertTrue(line.contains("network=10"))
        assertTrue(line.contains("disk=2"))
        assertTrue(line.contains("wallMs=800"))
        assertTrue(line.contains("decodedAvg=780x1170"))
    }
}
