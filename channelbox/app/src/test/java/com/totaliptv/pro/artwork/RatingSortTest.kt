package com.totaliptv.pro.artwork

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RatingSortTest {

    @Test
    fun rankOrderIsTotalAndNullsSortLast() {
        val items = listOf(
            item("c", "Same"),
            item("b", "Same"),
            item("d", "Other"),
            item("a", "Same")
        )
        val scores = mapOf("b" to 8.0, "c" to 8.0, "d" to 8.0)
        val sorted = MediaOrder.sortByRank(items, scores)
        assertEquals(listOf("d", "b", "c", "a"), sorted.map { it.id })
        val order = MediaOrder.byRankDescending(scores)
        for (left in items) {
            for (right in items) {
                assertEquals(0, order.compare(left, right) + order.compare(right, left))
            }
        }
        assertEquals(1, MediaOrder.compareScoreDescending(null, 1.0))
        assertEquals(-1, MediaOrder.compareScoreDescending(1.0, null))
        assertEquals(0, MediaOrder.compareScoreDescending(null, null))
        assertTrue(MediaOrder.compareScoreDescending(9.0, 8.0) < 0)
    }

    @Test
    fun nameAndAddedOrdersBreakTiesOnId() {
        val same = listOf(item("b", "Same"), item("a", "Same"))
        assertEquals(listOf("a", "b"), same.sortedWith(MediaOrder.byName(false)).map { it.id })
        assertEquals(listOf("b", "a"), same.sortedWith(MediaOrder.byName(true)).map { it.id })
        val added = listOf(
            item("b", "B", addedMs = 5L),
            item("a", "A", addedMs = 5L)
        )
        assertEquals(listOf("a", "b"), added.sortedWith(MediaOrder.byAddedDescending()).map { it.id })
    }

    @Test
    fun paceRanksImmediatelyWhenIdleAndOtherwiseAtMostEveryTenSeconds() {
        assertEquals(0L, RankPace.delayMs(lastRankMs = -1L, nowMs = 5_000L, settled = false))
        assertEquals(0L, RankPace.delayMs(lastRankMs = 1_000L, nowMs = 2_000L, settled = true))
        assertEquals(9_000L, RankPace.delayMs(lastRankMs = 1_000L, nowMs = 2_000L, settled = false))
        assertEquals(0L, RankPace.delayMs(lastRankMs = 1_000L, nowMs = 11_000L, settled = false))
    }

    @Test
    fun concurrentRatingWritesDoNotBreakSort() {
        val dir = File.createTempFile("tmdb-sort", ".dir")
        dir.delete()
        dir.mkdirs()
        val engine = TmdbRatingEngine(
            storeFile = File(dir, "tmdb-ratings.json"),
            autoDrain = false,
            pauseMs = 0,
            apiKey = { "0123456789abcdef0123456789abcdef" }
        )
        val items = (0 until 300).map { item("m$it", "Title $it", tmdbId = "$it") }
        val running = AtomicBoolean(true)
        val writerError = AtomicReference<Throwable?>(null)
        val writers = List(4) {
            Thread {
                var n = 0
                try {
                    while (running.get()) {
                        val id = n % items.size
                        engine.testingPut(
                            "movie:$id",
                            TmdbRating(
                                average = (n % 10) + 0.25,
                                votes = 40,
                                found = true,
                                fetchedAtMs = 1L,
                                tmdbId = "$id"
                            )
                        )
                        n++
                    }
                } catch (thrown: Throwable) {
                    writerError.compareAndSet(null, thrown)
                }
            }
        }
        writers.forEach { it.start() }
        try {
            repeat(40) {
                val scores = engine.rankScores(items)
                val sorted = MediaOrder.sortByRank(items, scores)
                assertEquals(items.map { it.id }.toSet(), sorted.map { it.id }.toSet())
                val order = MediaOrder.byRankDescending(scores)
                for (index in 0 until sorted.lastIndex) {
                    assertTrue(order.compare(sorted[index], sorted[index + 1]) <= 0)
                }
            }
        } finally {
            running.set(false)
            writers.forEach { it.join(5_000) }
        }
        assertNull(writerError.get())
    }

    private fun item(
        id: String,
        name: String,
        addedMs: Long? = null,
        tmdbId: String? = null
    ) = MediaItem(
        id = id,
        name = name,
        streamUrl = "http://example/$id",
        categoryId = "c",
        kind = ContentKind.VOD,
        addedMs = addedMs,
        tmdbId = tmdbId
    )
}
