package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveChannelMappingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun el(raw: String): JsonElement = json.parseToJsonElement(raw)

    private fun live(
        streamId: Int,
        name: String,
        num: Int? = null,
        categoryIds: List<String> = emptyList(),
        epgChannelId: String? = null
    ): MediaItem = MediaItem(
        id = "live-$streamId",
        name = name,
        streamUrl = "http://example.test/live/u/p/$streamId.m3u8",
        categoryId = categoryIds.firstOrNull(),
        kind = ContentKind.LIVE,
        xtreamStreamId = streamId,
        channelNum = num,
        epgChannelId = epgChannelId,
        categoryIds = categoryIds
    )

    @Test
    fun guideAndLiveListsAreIdenticalForSameFilter() {
        val catalog = listOf(
            live(88321, "US BET East", num = 42, categoryIds = listOf("live-usa", "live-ent"), epgChannelId = "BET.us"),
            live(100, "CNN", num = 5, categoryIds = listOf("live-usa", "live-news")),
            live(200, "Discovery", num = 9, categoryIds = listOf("live-doc")),
            live(300, "Men in Black", num = 1, categoryIds = listOf("live-movies"))
        )
        val liveList = LiveChannelMapping.filterLiveChannels(catalog, "live-usa", "")
        val guideList = LiveChannelMapping.filterLiveChannels(catalog, "live-usa", "")
        assertEquals(liveList.map { it.xtreamStreamId }, guideList.map { it.xtreamStreamId })
        assertEquals(listOf(100, 88321), liveList.map { it.xtreamStreamId })
    }

    @Test
    fun sortUsesBouquetNumNotListIndexOrName() {
        val catalog = listOf(
            live(9, "Zeta", num = 30),
            live(8, "Alpha", num = 10),
            live(7, "Middle", num = 20)
        )
        val ordered = LiveChannelMapping.filterLiveChannels(catalog)
        assertEquals(listOf("Alpha", "Middle", "Zeta"), ordered.map { it.name })
        assertEquals(listOf(8, 7, 9), ordered.map { it.xtreamStreamId })
    }

    @Test
    fun categoryArrayMembershipShowsChannelInEveryBouquet() {
        val bet = live(88321, "US BET East", num = 42, categoryIds = listOf("live-usa", "live-ent"))
        val catalog = listOf(bet, live(1, "Other", num = 1, categoryIds = listOf("live-news")))
        assertEquals(listOf(88321), LiveChannelMapping.filterLiveChannels(catalog, "live-usa").map { it.xtreamStreamId })
        assertEquals(listOf(88321), LiveChannelMapping.filterLiveChannels(catalog, "live-ent").map { it.xtreamStreamId })
    }

    @Test
    fun truncatingGuideWouldMisalignRows() {
        val catalog = (1..250).map { i ->
            live(streamId = 1000 + i, name = "Ch $i", num = i, categoryIds = listOf("live-all"))
        }
        val liveList = LiveChannelMapping.filterLiveChannels(catalog, "live-all")
        val oldGuide = liveList.take(40)
        assertEquals(250, liveList.size)
        assertNotEquals(liveList.size, oldGuide.size)
        assertEquals(1250, liveList.last().xtreamStreamId)
        assertEquals(1040, oldGuide.last().xtreamStreamId)
    }

    @Test
    fun parseStreamIdFromStringOrIntNeverUsesNum() {
        val fromInt = LiveChannelMapping.parseXtreamLiveIds(
            streamIdEl = el("88321"),
            numEl = el("42"),
            epgChannelIdEl = el("\"BET.us\""),
            categoryIdEl = el("\"5\"")
        )
        assertEquals(88321, fromInt.streamId)
        assertEquals(42, fromInt.channelNum)
        assertEquals("BET.us", fromInt.epgChannelId)

        val missingStreamUsesIdNotNum = LiveChannelMapping.parseXtreamLiveIds(
            streamIdEl = null,
            numEl = el("42"),
            epgChannelIdEl = null,
            categoryIdEl = null,
            fallbackIdEl = el("555")
        )
        assertEquals(555, missingStreamUsesIdNotNum.streamId)

        val numOnlyIsNotAStream = LiveChannelMapping.parseXtreamLiveIds(
            streamIdEl = null,
            numEl = el("42"),
            epgChannelIdEl = null,
            categoryIdEl = null
        )
        assertEquals(0, numOnlyIsNotAStream.streamId)
        assertNull(LiveChannelMapping.parseEpgChannelId(el("null")))
        assertTrue(LiveChannelMapping.parseRawCategoryIds(el("""["5","12"]""")) == listOf("5", "12"))
        assertEquals(listOf("5", "12"), LiveChannelMapping.parseRawCategoryIds(el("\"5,12\"")))
        assertEquals(listOf("usa", "ent"), LiveChannelMapping.parseRawCategoryIds(null, el("""["usa","ent"]""")))
        assertFalse(fromInt.streamId == fromInt.channelNum)
    }
}
