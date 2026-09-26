package com.totaliptv.pro.desktop.artwork

import com.totaliptv.pro.desktop.data.Catalog
import com.totaliptv.pro.desktop.data.ContentKind
import com.totaliptv.pro.desktop.data.MediaItem
import com.totaliptv.pro.desktop.ui.pickTopRatedMovies
import com.totaliptv.pro.desktop.ui.pickTopRatedSeries
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RatingOrderTest {

    @Test
    fun tiesBreakByTitleThenIdAndNanCannotLead() {
        val items = listOf(
            movie("b", "Beta", 8.0),
            movie("c", "Alpha", 8.0),
            movie("a", "alpha", 8.0),
            movie("z", "Zebra", 9.0),
            movie("low", "Low", Double.NaN)
        )
        val sorted = RatingOrder.sortByRating(items) { it.rating!!.toDouble() }
        assertEquals(listOf("z", "a", "c", "b", "low"), sorted.map { it.id })
        val recent = RatingOrder.sortRecent(
            listOf(
                movie("old", "Same", 1.0, added = 10, streamId = 2),
                movie("new-b", "Same", 1.0, added = 20, streamId = 1),
                movie("new-a", "Same", 1.0, added = 20, streamId = 5)
            )
        )
        assertEquals(listOf("new-a", "new-b", "old"), recent.map { it.id })
    }

    @Test
    fun mutatingScoresDuringSortDoesNotThrow() {
        val items = (0 until 400).map { index ->
            movie("id-$index", "Title ${index.toString().padStart(4, '0')}", 5.0)
        }
        val scores = ConcurrentHashMap<String, Double>()
        items.forEach { scores[it.id] = if (it.id.hashCode() % 2 == 0) 8.0 else 3.0 }
        val running = AtomicBoolean(true)
        val writer = Thread {
            var n = 0
            while (running.get()) {
                val item = items[n % items.size]
                scores[item.id] = when (n % 4) {
                    0 -> 9.5
                    1 -> 0.0
                    2 -> Double.NaN
                    else -> 6.2
                }
                n++
            }
        }
        writer.isDaemon = true
        writer.start()
        try {
            repeat(12) {
                val sorted = RatingOrder.sortByRating(items) { item ->
                    val value = scores[item.id] ?: 0.0
                    scores[item.id] = if (value > 5.0) 1.0 else 9.0
                    value
                }
                assertEquals(items.size, sorted.size)
                assertEquals(items.map { it.id }.toSet(), sorted.map { it.id }.toSet())
            }
            val row = pickTopRatedMovies(Catalog(vodItems = items.take(40))) { item ->
                val value = scores[item.id] ?: 0.0
                scores[item.id] = if ((item.id.hashCode() and 1) == 0) 0.0 else 8.8
                value
            }
            assertTrue(row.items.size <= 11)
            pickTopRatedSeries(Catalog(seriesItems = items.take(8).map { it.copy(kind = ContentKind.SERIES) }))
        } finally {
            running.set(false)
            writer.join(2000)
        }
    }

    private fun movie(
        id: String,
        name: String,
        rating: Double,
        added: Long = 0,
        streamId: Int? = null
    ) = MediaItem(
        id = id,
        name = name,
        streamUrl = "http://example/$id",
        categoryId = "c",
        kind = ContentKind.VOD,
        rating = rating.toString(),
        country = "United States",
        year = 2024,
        addedEpoch = added,
        xtreamStreamId = streamId
    )
}
