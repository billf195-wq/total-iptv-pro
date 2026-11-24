package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PosterPrefetchTest {
    private fun item(
        id: String,
        kind: ContentKind = ContentKind.VOD,
        poster: String? = null,
        logo: String? = null,
        backdrop: String? = null,
    ) = MediaItem(
        id = id,
        name = id,
        streamUrl = "s",
        categoryId = "c",
        kind = kind,
        logoUrl = logo,
        posterUrl = poster,
        backdropUrl = backdrop,
    )

    @Test
    fun urlsAreUniqueAndPreferPosterThenLogoAndIncludeBackdrop() {
        val items = listOf(
            item("a", poster = "http://p/a.png", logo = "http://p/logo-a.png", backdrop = "http://p/a-wide.png"),
            item("b", logo = "http://p/b.png"),
            item("c", poster = "http://p/a.png"),
            item("blank", poster = "  ", logo = "", backdrop = ""),
            item("s", kind = ContentKind.SERIES, poster = "http://p/s.png"),
        )
        val urls = PosterPrefetchPlanner.urlsFor(items)
        assertEquals(
            listOf("http://p/a.png", "http://p/a-wide.png", "http://p/b.png", "http://p/s.png"),
            urls,
        )
    }

    @Test
    fun resumeIndexRestartsOnNewSourceAndClamps() {
        assertEquals(0, PosterPrefetchPlanner.resumeIndex(null, 5, "src", 10))
        assertEquals(0, PosterPrefetchPlanner.resumeIndex("old", 5, "src", 10))
        assertEquals(5, PosterPrefetchPlanner.resumeIndex("src", 5, "src", 10))
        assertEquals(10, PosterPrefetchPlanner.resumeIndex("src", 99, "src", 10))
        assertEquals(0, PosterPrefetchPlanner.resumeIndex("src", 3, "src", 0))
    }

    @Test
    fun runnerSkipsCachedFetchesTheRestAndResumesFromCursor() = runBlocking {
        val cached = mutableSetOf("http://p/1.png")
        val fetched = mutableListOf<String>()
        val statsLog = mutableListOf<PosterPrefetchStats>()
        val runner = PosterPrefetchRunner(
            isPlaybackActive = { false },
            awaitPlaybackIdle = {},
            isCached = { it in cached },
            fetchToDisk = { url -> fetched += url; cached += url; true },
            diskBytes = { 123L },
            onStats = { statsLog += it },
            idleDelayMs = 0,
        )
        val urls = listOf("http://p/1.png", "http://p/2.png", "http://p/3.png")
        val first = runner.run(urls, "src", startIndex = 0)
        assertEquals(1, first.skipped)
        assertEquals(2, first.done)
        assertEquals(0, first.failed)
        assertEquals(3, first.total)
        assertEquals(listOf("http://p/2.png", "http://p/3.png"), fetched)
        assertFalse(first.running)
        assertEquals(123L, first.diskBytes)

        fetched.clear()
        val second = runner.run(urls, "src", startIndex = 0)
        assertEquals(3, second.skipped)
        assertEquals(0, second.done)
        assertTrue(fetched.isEmpty())

        fetched.clear()
        cached.remove("http://p/3.png")
        val resumed = runner.run(urls, "src", startIndex = 2)
        assertEquals(listOf("http://p/3.png"), fetched)
        assertEquals(1, resumed.done)
        assertEquals(0, resumed.skipped)
    }

    @Test
    fun runnerPausesWhilePlaybackIsActive() = runBlocking {
        var playing = true
        var awaited = 0
        val fetched = mutableListOf<String>()
        val paused = mutableListOf<Boolean>()
        val runner = PosterPrefetchRunner(
            isPlaybackActive = { playing },
            awaitPlaybackIdle = { awaited++; playing = false },
            isCached = { false },
            fetchToDisk = { url -> fetched += url; true },
            diskBytes = { 0L },
            onStats = { paused += it.pausedForPlayback },
            idleDelayMs = 0,
        )
        runner.run(listOf("http://p/1.png"), "src")
        assertEquals(1, awaited)
        assertEquals(listOf("http://p/1.png"), fetched)
        assertTrue(paused.any { it })
        assertFalse(paused.last())
    }

    @Test
    fun statusJsonHasCountsAndNoUrlFields() {
        val dir = createTempDir(prefix = "prefetch-test")
        try {
            val file = File(dir, "status.json")
            PosterPrefetch.writeStatus(
                file,
                PosterPrefetchStats(
                    sourceId = "lib",
                    total = 10,
                    done = 3,
                    skipped = 6,
                    failed = 1,
                    diskBytes = 4096,
                    running = false,
                    pausedForPlayback = false,
                    cursor = 10,
                ),
            )
            val raw = file.readText()
            assertTrue(raw.contains("\"done\":3"))
            assertTrue(raw.contains("\"skipped\":6"))
            assertTrue(raw.contains("\"diskBytes\":4096"))
            assertFalse(raw.contains("http://"))
            assertFalse(raw.contains("posterUrl"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun appStartsPrefetchAfterListsAndKeepsDiskOnlyFetch() {
        val app = File("src/main/java/com/totaliptv/pro/TotalIptvProApp.kt").readText()
        assertTrue(app.contains("PosterPrefetch.start("))
        val pref = File("src/main/java/com/totaliptv/pro/data/PosterPrefetch.kt").readText()
        assertTrue(pref.contains("vodLoading"))
        assertTrue(pref.contains("job?.cancel()"))
        assertTrue(pref.contains("PosterPrefetchPolicy.shouldLaunch"))
        assertTrue(pref.contains("job?.isActive"))
        assertTrue(pref.contains("memoryCachePolicy(CachePolicy.DISABLED)"))
        assertTrue(pref.contains("diskCachePolicy(CachePolicy.ENABLED)"))
        assertTrue(pref.contains("PlaybackGate"))
        assertTrue(pref.contains("Dispatchers.IO"))
        assertFalse(pref.contains("memoryCachePolicy(CachePolicy.ENABLED)"))
        val budget = File("src/main/java/com/totaliptv/pro/util/MemoryBudget.kt").readText()
        assertTrue(budget.contains("fun coilDiskCacheBytes(): Long = 512L * 1024 * 1024"))
        assertTrue(budget.contains("fun coilMemoryCacheBytes(): Int = 64 * 1024 * 1024"))
    }

    @Test
    fun policyDoesNotCancelActiveJobWhenListsAreIdle() {
        assertTrue(PosterPrefetchPolicy.shouldCancel(true))
        assertFalse(PosterPrefetchPolicy.shouldCancel(false))
        assertFalse(PosterPrefetchPolicy.shouldLaunch(loading = false, jobActive = true))
        assertTrue(PosterPrefetchPolicy.shouldLaunch(loading = false, jobActive = false))
        assertFalse(PosterPrefetchPolicy.shouldLaunch(loading = true, jobActive = false))
        assertFalse(PosterPrefetchPolicy.shouldLaunch(loading = true, jobActive = true))
    }
}
