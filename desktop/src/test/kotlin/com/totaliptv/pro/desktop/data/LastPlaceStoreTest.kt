package com.totaliptv.pro.desktop.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LastPlaceStoreTest {
    @Test
    fun roundTripsBrowseWithoutStoringAUrl() {
        val dir = kotlin.io.path.createTempDirectory("last-place").toFile()
        try {
            val store = LastPlaceStore(File(dir, "last-place.json"))
            store.saveBrowse(screen = LastPlace.SCREEN_MOVIES, categoryId = "vod-svc-netflix")
            assertTrue(store.flush())
            val loaded = LastPlaceStore(File(dir, "last-place.json")).load()
            assertEquals(LastPlace.SCREEN_MOVIES, loaded?.screen)
            assertEquals("vod-svc-netflix", loaded?.categoryId)
            assertFalse(dir.resolve("last-place.json").readText().contains("http"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun roundTripsScrollWithoutStoringAUrl() {
        val dir = kotlin.io.path.createTempDirectory("last-place-scroll").toFile()
        try {
            val store = LastPlaceStore(File(dir, "last-place.json"))
            store.saveBrowse(
                screen = LastPlace.SCREEN_MOVIES,
                categoryId = "vod-svc-netflix",
                firstVisibleIndex = 18,
                firstVisibleOffset = 42,
                rowScroll = listOf(RowScroll("movies:row-5", 7, 12), RowScroll("chips:VOD", 3, 0)),
                focusedItemId = "vod-99",
                setFocusedItemId = true
            )
            assertTrue(store.flush())
            val loaded = LastPlaceStore(File(dir, "last-place.json")).load()
            assertEquals(18, loaded?.firstVisibleIndex)
            assertEquals(42, loaded?.firstVisibleOffset)
            assertEquals(2, loaded?.rowScroll?.size)
            assertEquals("movies:row-5", loaded?.rowScroll?.first()?.key)
            assertEquals(7, loaded?.rowScroll?.first()?.index)
            assertEquals(12, loaded?.rowScroll?.first()?.offset)
            assertEquals("vod-99", loaded?.focusedItemId)
            val text = dir.resolve("last-place.json").readText()
            assertFalse(text.contains("http"))
            assertFalse(text.contains("://"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun saveBrowseWithoutScrollKeepsExistingSpot() {
        val dir = kotlin.io.path.createTempDirectory("last-place-keep").toFile()
        try {
            val store = LastPlaceStore(File(dir, "last-place.json"))
            store.saveBrowse(LastPlace.SCREEN_MOVIES, "cat-a", firstVisibleIndex = 10, firstVisibleOffset = 5)
            store.saveBrowse(LastPlace.SCREEN_MOVIES, "cat-a")
            assertTrue(store.flush())
            val loaded = store.load()
            assertEquals(10, loaded?.firstVisibleIndex)
            assertEquals(5, loaded?.firstVisibleOffset)
        } finally {
            dir.deleteRecursively()
        }
    }
}
