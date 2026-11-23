package com.totaliptv.pro.ui.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
        // Classic Home is hero + Continue watching (+ Favorites). Newly added stays in Movies/Series.
        assertFalse(classic.contains("HomeShelfLabel(\"Newly added\")"))
        assertFalse(classic.contains("onSeeAllNewlyAdded"))
        assertFalse(classic.contains("SeeAllPosterTile"))
        assertFalse(classic.contains("HomeShelfLabel(\"Top rated\")"))
        assertFalse(classic.contains("onSeeAllTopRated"))
        assertFalse(classic.contains("topPicksHasMore"))
        assertTrue(classic.contains("HomeShelfLabel(\"Continue watching\")"))
        assertTrue(classic.contains("HomeShelfLabel(\"Favorites\")"))
        assertTrue(classic.contains("onLeftToRail"))
        assertTrue(classic.contains("Key.DirectionLeft"))
        assertTrue(classic.contains("HomeHeroBanner("))
        assertTrue(classic.contains("HeroBannerPick"))
        assertFalse(classic.contains("newlyAddedMovies(Int.MAX_VALUE)"))
        assertFalse(classic.contains("newlyAddedMovies(24)"))
        // Full Newly added lists stay reachable from the Movies/Series sidebar.
        val seeAllTile = File("src/tv/java/com/totaliptv/pro/ui/components/Components.kt").readText()
            .substringAfter("fun SeeAllPosterTile")
            .substringBefore("fun ChannelGridCard")
                assertEquals(1, seeAllTile.split("text = label").size - 1)
        val cats = File("src/main/java/com/totaliptv/pro/data/CatalogCategories.kt").readText()
        assertTrue(cats.contains("const val HOME_ROW_LIMIT = 20"))
        assertTrue(cats.contains("const val ALL_NAME = \"See all\""))

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
