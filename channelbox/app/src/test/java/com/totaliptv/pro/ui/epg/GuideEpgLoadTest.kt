package com.totaliptv.pro.ui.epg

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.EpgChannelRow
import com.totaliptv.pro.data.model.EpgNowNext
import com.totaliptv.pro.data.model.EpgProgram
import com.totaliptv.pro.data.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuideEpgLoadTest {

    private val ids = (0 until 80).map { "ch-$it" }

    @Test
    fun focusedThenVisibleThenRestSkipsStarted() {
        val started = setOf("ch-12", "ch-13")
        val order = GuideEpgLoad.fetchOrder(
            channelIds = ids,
            firstVisibleIndex = 10,
            focusedId = "ch-55",
            alreadyStarted = started
        )
        assertEquals(55, order.first())
        assertEquals("ch-55", ids[order.first()])
        assertEquals(
            listOf(55) + (10 until 30).filter { it != 12 && it != 13 },
            order.take(1 + 20 - 2)
        )
        assertFalse(order.any { ids[it] in started })
        assertEquals(ids.size - started.size, order.size)
        assertTrue(order.contains(0))
        assertTrue(order.contains(79))
    }

    @Test
    fun nextBatchCapsAtParallelAndPrefersOnScreen() {
        val batch = GuideEpgLoad.nextBatch(
            channelIds = ids,
            firstVisibleIndex = 40,
            focusedId = null,
            alreadyStarted = emptySet()
        )
        assertEquals(GuideEpgLoad.PARALLEL, batch.size)
        assertEquals((40 until 50).toList(), batch)
    }

    @Test
    fun emptyAndAllStartedYieldNothing() {
        assertTrue(GuideEpgLoad.fetchOrder(emptyList(), 0, null, emptySet()).isEmpty())
        assertTrue(
            GuideEpgLoad.nextBatch(ids, 0, "ch-0", alreadyStarted = ids.toSet()).isEmpty()
        )
    }

    @Test
    fun applyRowReplacesMatchingChannelOnly() {
        fun live(id: String, name: String) = MediaItem(
            id = id,
            name = name,
            streamUrl = "http://example.test/live/u/p/$id.ts",
            categoryId = "live-1",
            kind = ContentKind.LIVE,
            xtreamStreamId = id.removePrefix("ch-").toInt()
        )
        val a = live("ch-1", "CNN")
        val b = live("ch-2", "HLN")
        val rows = listOf(EpgChannelRow(channel = a), EpgChannelRow(channel = b))
        val filled = EpgChannelRow(
            channel = a,
            programs = listOf(EpgProgram("Newsroom", startMs = 1L, endMs = 2L)),
            nowNext = EpgNowNext(now = EpgProgram("Newsroom", startMs = 1L, endMs = 2L))
        )
        val next = GuideEpgLoad.applyRow(rows, filled)
        assertEquals(1, next[0].programs.size)
        assertEquals("Newsroom", next[0].programs[0].title)
        assertTrue(next[1].programs.isEmpty())
        assertEquals("ch-2", next[1].channel.id)
    }

    @Test
    fun guideScreenPaintsThenFillsOnMainWithoutAwaitAll() {
        val guide = java.io.File("src/tv/java/com/totaliptv/pro/ui/epg/EpgGuideScreen.kt").readText()
        val repo = java.io.File("src/main/java/com/totaliptv/pro/data/repo/CatalogRepository.kt").readText()
        assertTrue(guide.contains("peekCachedGuideRow"))
        assertTrue(guide.contains("loadGuideRow"))
        assertTrue(guide.contains("GuideEpgLoad.fetchOrder"))
        assertTrue(guide.contains("GuideEpgLoad.applyRow"))
        assertTrue(guide.contains("supervisorScope"))
        assertTrue(guide.contains("withContext(Dispatchers.IO)"))
        assertTrue(guide.contains("state = listState"))
        assertTrue(guide.contains("loading = false"))
        assertFalse(guide.contains("loadGuideRows"))
        assertFalse(guide.contains("awaitAll("))
        assertFalse(guide.contains("launch(Dispatchers.IO)"))
        assertFalse(guide.contains("Channel<EpgChannelRow>"))
        assertFalse(guide.contains("scrollToItem"))
        assertTrue(guide.indexOf("loading = false") < guide.indexOf("loadGuideRow"))
        assertTrue(repo.contains("epgFetchSemaphore = Semaphore(10)"))
        assertTrue(repo.contains("fun peekCachedGuideRow"))
        assertTrue(repo.contains("suspend fun loadGuideRow"))
        assertTrue(repo.contains("xtreamApi.fetchShortEpg"))
        assertEquals(10, GuideEpgLoad.PARALLEL)
        assertEquals(20, GuideEpgLoad.VISIBLE_PREFETCH)
    }
}
