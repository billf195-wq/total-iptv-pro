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
}
