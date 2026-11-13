package com.totaliptv.pro.data.local

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LastPlaceStoreTest {
    @Test
    fun roundTripsBrowseAndPlaybackWithoutStoringAUrl() {
        val dir = createTempDir("last-place")
        try {
            val store = LastPlaceStore(File(dir, "last-place.json"))
            store.saveBrowse(
                screen = LastPlace.SCREEN_MOVIES,
                categoryId = "vod-svc-netflix",
                firstVisibleIndex = 22,
                focusedItemId = "vod-9"
            )
            store.savePlaying(
                itemId = "vod-9",
                kind = "VOD",
                title = "The Matrix",
                catalogId = "vod-9",
                positionMs = 120_000L
            )
            assertTrue(store.flush())
            val fresh = LastPlaceStore(File(dir, "last-place.json"))
            val place = fresh.load()
            assertEquals(LastPlace.SCREEN_MOVIES, place?.screen)
            assertEquals("vod-svc-netflix", place?.categoryId)
            assertEquals(22, place?.firstVisibleIndex)
            assertEquals("vod-9", place?.focusedItemId)
            assertEquals(true, place?.playing)
            assertEquals("vod-9", place?.playItemId)
            assertEquals(120_000L, place?.playPositionMs)
            val raw = File(dir, "last-place.json").readText()
            assertFalse(raw.contains("http://"))
            assertFalse(raw.contains("streamUrl"))
            assertFalse(raw.contains("password"))
            assertFalse(raw.contains("playUrl"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun clearPlayingKeepsTheBrowseSpot() {
        val dir = createTempDir("last-place-clear")
        try {
            val store = LastPlaceStore(File(dir, "last-place.json"))
            store.saveBrowse(LastPlace.SCREEN_LIVE, "live-news", 4, "live-2")
            store.savePlaying("live-2", "LIVE", "News", null, 0L)
            store.clearPlaying()
            assertTrue(store.flush())
            val place = LastPlaceStore(File(dir, "last-place.json")).load()
            assertEquals(LastPlace.SCREEN_LIVE, place?.screen)
            assertEquals("live-news", place?.categoryId)
            assertEquals(false, place?.playing)
            assertEquals("live-2", place?.playItemId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingFileIsEmpty() {
        val dir = createTempDir("last-place-missing")
        try {
            assertNull(LastPlaceStore(File(dir, "nope.json")).load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun roundTripsRowScrollAndOffset() {
        val dir = createTempDir("last-place-rows")
        try {
            val store = LastPlaceStore(File(dir, "last-place.json"))
            store.saveBrowse(
                screen = LastPlace.SCREEN_HOME,
                firstVisibleIndex = 5,
                firstVisibleOffset = 30,
                rowScroll = listOf(RowScroll("cw", 8, 4), RowScroll("new", 2, 0)),
                focusedItemId = "vod-12"
            )
            assertTrue(store.flush())
            val place = LastPlaceStore(File(dir, "last-place.json")).load()
            assertEquals(5, place?.firstVisibleIndex)
            assertEquals(30, place?.firstVisibleOffset)
            assertEquals(2, place?.rowScroll?.size)
            assertEquals("cw", place?.rowScroll?.first()?.key)
            assertEquals(8, place?.rowScroll?.first()?.index)
            assertEquals("vod-12", place?.focusedItemId)
            assertFalse(File(dir, "last-place.json").readText().contains("http"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun saveBrowseWithoutScrollKeepsExistingSpot() {
        val dir = createTempDir("last-place-keep")
        try {
            val store = LastPlaceStore(File(dir, "last-place.json"))
            store.saveBrowse(LastPlace.SCREEN_MOVIES, "cat-a", 10, "vod-1")
            store.saveBrowse(LastPlace.SCREEN_MOVIES, "cat-a")
            assertTrue(store.flush())
            val place = store.load()
            assertEquals(10, place?.firstVisibleIndex)
            assertEquals("vod-1", place?.focusedItemId)
        } finally {
            dir.deleteRecursively()
        }
    }
}
