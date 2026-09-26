package com.totaliptv.pro.ui.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeRowItemsTest {
    @Test
    fun homeRowsKeepEveryItemAndScroll() {
        val desktop = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
        val home = desktop.substringAfter("fun HomePane").substringBefore("fun LivePane")
        assertFalse(home.contains("take(8)"))
        assertFalse(home.contains("take(24)"))
        assertTrue(home.contains("TvLazyRow("))
        assertTrue(home.contains("tvItemsIndexed(resume,"))

        val helpers = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopHomeHelpers.kt").readText()
        assertFalse(helpers.contains("TOP_N"))
        assertFalse(helpers.contains(".take("))

        val classic = File("src/tv/java/com/totaliptv/pro/ui/home/HomeScreen.kt").readText()
        assertTrue(classic.contains("TvLazyRow("))
        assertTrue(classic.contains("newlyAddedMovies(Int.MAX_VALUE)"))
        assertFalse(classic.contains("newlyAddedMovies(24)"))

        val phone = File("src/phone/java/com/totaliptv/pro/ui/home/PhoneHomeScreen.kt").readText()
        assertTrue(phone.contains("LazyColumn("))
        assertTrue(phone.contains("LazyRow("))
        assertTrue(phone.contains("newlyAddedMovies(Int.MAX_VALUE)"))
        assertTrue(phone.contains("liveItems()"))
        assertFalse(phone.contains("liveItems(20)"))
        assertFalse(phone.contains("newlyAddedMovies(24)"))

        val root = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopAppRoot.kt").readText()
        assertFalse(root.contains("continueWatching(24)"))
        val store = File("src/main/java/com/totaliptv/pro/data/local/WatchProgressStore.kt").readText()
        assertTrue(store.contains("limit: Int = Int.MAX_VALUE"))
    }
}
