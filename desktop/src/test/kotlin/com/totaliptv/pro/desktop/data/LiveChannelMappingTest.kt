package com.totaliptv.pro.desktop.data

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
        num: Int = 0,
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
        assertEquals("CNN", liveList[0].name)
        assertEquals("US BET East", liveList[1].name)
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
        val usa = LiveChannelMapping.filterLiveChannels(catalog, "live-usa")
        val ent = LiveChannelMapping.filterLiveChannels(catalog, "live-ent")
        assertEquals(listOf(88321), usa.map { it.xtreamStreamId })
        assertEquals(listOf(88321), ent.map { it.xtreamStreamId })
        assertTrue(usa.none { it.name == "Other" })
    }

    @Test
    fun truncatingGuideWouldMisalignRows() {
        val catalog = (1..250).map { i ->
            live(streamId = 1000 + i, name = "Ch $i", num = i, categoryIds = listOf("live-all"))
        }
        val liveList = LiveChannelMapping.filterLiveChannels(catalog, "live-all")
        val oldGuide = liveList.take(200)
        assertEquals(250, liveList.size)
        assertNotEquals(liveList.size, oldGuide.size)
        assertEquals(liveList.take(200).map { it.xtreamStreamId }, oldGuide.map { it.xtreamStreamId })
        assertEquals(1250, liveList.last().xtreamStreamId)
        assertEquals(1200, oldGuide.last().xtreamStreamId)
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
        assertEquals(listOf("5"), fromInt.rawCategoryIds)

        val quoted = LiveChannelMapping.parseXtreamLiveIds(
            streamIdEl = el("\"9001\""),
            numEl = el("\"7\""),
            epgChannelIdEl = el("null"),
            categoryIdEl = el("12")
        )
        assertEquals(9001, quoted.streamId)
        assertEquals(7, quoted.channelNum)
        assertNull(quoted.epgChannelId)

        val missingStreamUsesIdNotNum = LiveChannelMapping.parseXtreamLiveIds(
            streamIdEl = null,
            numEl = el("42"),
            epgChannelIdEl = null,
            categoryIdEl = null,
            fallbackIdEl = el("555")
        )
        assertEquals(555, missingStreamUsesIdNotNum.streamId)
        assertEquals(42, missingStreamUsesIdNotNum.channelNum)

        val numOnlyIsNotAStream = LiveChannelMapping.parseXtreamLiveIds(
            streamIdEl = null,
            numEl = el("42"),
            epgChannelIdEl = null,
            categoryIdEl = null
        )
        assertEquals(0, numOnlyIsNotAStream.streamId)
    }

    @Test
    fun parseCategoryIdsFromArrayCommaStringAndIdsField() {
        val fromArray = LiveChannelMapping.parseRawCategoryIds(el("""["5","12"]"""))
        assertEquals(listOf("5", "12"), fromArray)

        val fromComma = LiveChannelMapping.parseRawCategoryIds(el("\"5,12\""))
        assertEquals(listOf("5", "12"), fromComma)

        val merged = LiveChannelMapping.parseRawCategoryIds(el("\"5\""), el("[12,5]"))
        assertEquals(listOf("5", "12"), merged)
    }

    @Test
    fun liveStreamToMediaItemUsesStreamIdForPlayback() {
        val api = XtreamApi()
        val dto = json.decodeFromString<XtreamApi.LiveStream>(
            """
            {
              "num": 42,
              "name": "US BET East",
              "stream_id": "88321",
              "epg_channel_id": "BET.us",
              "category_id": ["usa", "ent"],
              "direct_source": ""
            }
            """.trimIndent()
        )
        val item = api.liveStreamToMediaItem(
            dto,
            host = "http://panel.example",
            username = "user",
            password = "pass",
            liveCategories = listOf(
                Category("live-usa", "USA", ContentKind.LIVE),
                Category("live-ent", "Entertainment", ContentKind.LIVE)
            )
        )
        checkNotNull(item)
        assertEquals("live-88321", item.id)
        assertEquals(88321, item.xtreamStreamId)
        assertEquals(42, item.channelNum)
        assertEquals("BET.us", item.epgChannelId)
        assertEquals(listOf("live-usa", "live-ent"), item.categoryIds)
        assertEquals("http://panel.example/live/user/pass/88321.m3u8", item.streamUrl)
        assertFalse(item.streamUrl.contains("/42.m3u8"))
        assertEquals("USA", item.groupTitle)
    }

    @Test
    fun queryDoesNotChangeIdentityOnlyMembership() {
        val catalog = listOf(
            live(1, "CNN HD", num = 1, categoryIds = listOf("live-news")),
            live(2, "BBC", num = 2, categoryIds = listOf("live-news"))
        )
        val liveList = LiveChannelMapping.filterLiveChannels(catalog, "live-news", "cnn")
        val guideList = LiveChannelMapping.filterLiveChannels(catalog, "live-news", "cnn")
        assertEquals(liveList.map { LiveChannelMapping.liveKey(it) }, guideList.map { LiveChannelMapping.liveKey(it) })
        assertEquals(listOf(1), liveList.map { it.xtreamStreamId })
    }
}
