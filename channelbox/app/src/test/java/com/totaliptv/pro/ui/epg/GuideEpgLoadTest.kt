package com.totaliptv.pro.ui.epg

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.EpgChannelRow
import com.totaliptv.pro.data.model.EpgNowNext
import com.totaliptv.pro.data.model.EpgProgram
import com.totaliptv.pro.data.model.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuideEpgLoadTest {

    private val ids = (0 until 80).map { "ch-$it" }

    @Test
    fun visibleWindowDoesNotFetchTheRestOfALargeLineup() {
        val big = (0 until 4401).map { "ch-$it" }
        val order = GuideEpgLoad.fetchOrder(
            channelIds = big,
            firstVisibleIndex = 100,
            visibleCount = 8,
            focusedId = "ch-102",
            alreadyStarted = setOf("ch-101")
        )
        assertEquals(102, order.first())
        assertTrue(order.size <= 8 + GuideEpgLoad.BUFFER_ROWS)
        assertFalse(order.contains(0))
        assertFalse(order.contains(4000))
        assertFalse(order.any { big[it] == "ch-101" })
    }

    @Test
    fun nextBatchCapsAtParallelAndPrefersOnScreen() {
        val batch = GuideEpgLoad.nextBatch(
            channelIds = ids,
            firstVisibleIndex = 40,
            visibleCount = 8,
            focusedId = null,
            alreadyStarted = emptySet()
        )
        assertEquals(GuideEpgLoad.PARALLEL, batch.size)
        assertEquals((40 until 43).toList(), batch)
    }

    @Test
    fun emptyAndAllStartedYieldNothing() {
        assertTrue(GuideEpgLoad.fetchOrder(emptyList(), 0, 8, null, emptySet()).isEmpty())
        assertTrue(
            GuideEpgLoad.nextBatch(ids, 0, 8, "ch-0", alreadyStarted = ids.toSet()).isEmpty()
        )
    }

    @Test
    fun gateStaysWithinConcurrencyAndBacksOffOn429() {
        runBlocking {
            val gate = EpgRequestGate(maxInFlight = 3)
            val jobs = List(9) {
                launch {
                    gate.acquire()
                    delay(40)
                    gate.release()
                }
            }
            jobs.forEach { it.join() }
            assertTrue(gate.peakInFlight <= 3)
            assertEquals(9, gate.started)
        }
        runBlocking {
            var now = 1_000L
            val slept = mutableListOf<Long>()
            val gate = EpgRequestGate(
                maxInFlight = 2,
                now = { now },
                sleeper = { ms ->
                    slept += ms
                    now += ms
                }
            )
            gate.acquire()
            gate.onRateLimited()
            gate.release()
            gate.acquire()
            gate.release()
            assertTrue(slept.any { it >= EpgRequestGate.BACKOFF_START_MS })
            assertTrue(gate.peakInFlight <= 2)
        }
    }

    @Test
    fun cursorStepsProgramsAndKeepsTheColumn() {
        val programs = listOf(
            EpgProgram("A", startMs = 0L, endMs = 3_600_000L),
            EpgProgram("B", startMs = 3_600_000L, endMs = 7_200_000L)
        )
        val next = GuideCursor.step(programs, cursorMs = 10L, direction = 1, windowStart = 0L, windowEnd = 8_000_000L)
        assertEquals(3_600_000L, next)
        val back = GuideCursor.step(programs, cursorMs = next, direction = -1, windowStart = 0L, windowEnd = 8_000_000L)
        assertEquals(0L, back)
        assertEquals("B", GuideCursor.programAt(programs, 3_600_000L)?.title)
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
        assertTrue(guide.contains("prepareBulkGuideEpg"))
        assertTrue(guide.contains("debounce"))
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
        assertFalse(repo.contains("Semaphore(10)"))
        assertTrue(repo.contains("prepareBulkGuideEpg"))
        assertTrue(repo.contains("fun peekCachedGuideRow"))
        assertTrue(repo.contains("suspend fun loadGuideRow"))
        assertTrue(repo.contains("xtreamApi.fetchShortEpg"))
        assertEquals(3, GuideEpgLoad.PARALLEL)
        assertEquals(4, GuideEpgLoad.BUFFER_ROWS)
    }
}
