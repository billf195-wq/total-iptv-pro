package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.EpgProgram
import com.totaliptv.pro.data.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveEpgBindingTest {

    private val chicagoNow = 1_727_000_000_000L

    private fun live(
        streamId: Int,
        name: String,
        epgChannelId: String? = "usa-movies.generic",
        categoryIds: List<String> = listOf("live-usa-movies")
    ): MediaItem = MediaItem(
        id = "live-$streamId",
        name = name,
        streamUrl = "http://hudv.net:80/live/u/p/$streamId.m3u8",
        categoryId = categoryIds.first(),
        kind = ContentKind.LIVE,
        xtreamStreamId = streamId,
        epgChannelId = epgChannelId,
        categoryIds = categoryIds
    )

    private fun prog(title: String, start: Long, end: Long, sid: Int = 0): EpgProgram =
        EpgProgram(
            title = title,
            startMs = start,
            endMs = end,
            id = "$sid-$start",
            channelStreamId = sid
        )

    @Test
    fun usaMoviesSharedXmltvShowsTunedMastersNotGodfatherNow() {
        val masters = live(341, "Masters of the Universe")
        val other = live(352, "Conan the Barbarian")
        val siblings = listOf(masters, other)
        val shared = listOf(
            prog("The Godfather", chicagoNow - 30 * 60_000L, chicagoNow + 90 * 60_000L, sid = 341)
        )
        assertTrue(LiveEpgBinding.sharesEpgChannelId(masters, siblings))
        assertFalse(LiveEpgBinding.titlesMatch(masters.name, "The Godfather"))
        val bound = LiveEpgBinding.bindForDisplay(masters, shared, siblings, chicagoNow)
        val nowTitle = bound.find { it.contains(chicagoNow) }?.title
        assertEquals("Masters of the Universe", nowTitle)
        assertTrue(bound.none { it.contains(chicagoNow) && it.title == "The Godfather" })
    }

    @Test
    fun matchingNetworkTitleKeepsProviderNow() {
        val cnn = live(3, "CNN HD", epgChannelId = "CNN.us")
        val programs = listOf(
            prog("CNN Newsroom", chicagoNow - 10 * 60_000L, chicagoNow + 20 * 60_000L, sid = 3)
        )
        val bound = LiveEpgBinding.bindForDisplay(cnn, programs, listOf(cnn), chicagoNow)
        assertEquals("CNN Newsroom", bound.find { it.contains(chicagoNow) }?.title)
    }

    @Test
    fun epgFetchStaysKeyedByStreamIdNeverEpgChannelId() {
        val repo = java.io.File("src/main/java/com/totaliptv/pro/data/repo/CatalogRepository.kt").readText()
        assertTrue(repo.contains("xtreamApi.fetchShortEpg"))
        assertTrue(repo.contains("LiveEpgBinding.bindForDisplay"))
        assertFalse(repo.contains("fetchShortEpg(creds, item.epgChannelId"))
        val api = java.io.File("src/main/java/com/totaliptv/pro/data/xtream/XtreamApi.kt").readText()
        assertTrue(api.contains("\"stream_id\" to streamId.toString()"))
        assertTrue(api.contains("parseServerInfoZone"))
    }

    @Test
    fun titlesMatchIgnoresTheAndYear() {
        assertTrue(LiveEpgBinding.titlesMatch("The Godfather (1972)", "Godfather"))
        assertTrue(LiveEpgBinding.titlesMatch("Masters of the Universe", "Masters Of The Universe HD"))
        assertFalse(LiveEpgBinding.titlesMatch("Masters of the Universe", "The Godfather"))
    }
}
