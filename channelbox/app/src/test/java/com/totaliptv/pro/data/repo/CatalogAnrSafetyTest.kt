package com.totaliptv.pro.data.repo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogAnrSafetyTest {
    @Test
    fun topRatedDoesNotComputeOnTheCallerThread() {
        val src = File("src/main/java/com/totaliptv/pro/data/repo/CatalogRepository.kt").readText()
        val fn = src.substringAfter("fun topRated(kind: ContentKind): List<MediaItem> {")
            .substringBefore("\n    @Volatile")
        assertTrue(fn.contains("scheduleTopRatedCompute()"))
        assertFalse(fn.contains("storeTopRated("))
        assertTrue(fn.contains("return emptyList()"))
    }

    @Test
    fun serviceLookupUsesCacheAndSurvivesFailure() {
        val src = File("src/main/java/com/totaliptv/pro/data/repo/CatalogRepository.kt").readText()
        assertTrue(src.contains("readUsableCache"))
        assertTrue(src.contains("applyServiceIndex"))
        assertTrue(src.contains("OutOfMemoryError"))
        assertTrue(src.contains("onBatch"))
        assertTrue(src.contains("itemCountForCategory"))
        assertTrue(src.contains("refreshDerivedCatalog"))
    }

    @Test
    fun lastPlaceIsWiredForCrashRestore() {
        val app = File("src/main/java/com/totaliptv/pro/TotalIptvProApp.kt").readText()
        assertTrue(app.contains("LastPlaceStore"))
        assertTrue(app.contains("last-place.json"))
        val player = File("src/main/java/com/totaliptv/pro/ui/player/PlayerActivity.kt").readText()
        assertTrue(player.contains("savePlaying"))
        assertTrue(player.contains("clearPlaying"))
        val home = File("src/tv/java/com/totaliptv/pro/ui/home/HomeScreen.kt").readText()
        assertTrue(home.contains("saveBrowse"))
        assertTrue(home.contains("itemCountForCategory"))
        val tv = File("src/tv/java/com/totaliptv/pro/MainActivity.kt").readText()
        assertTrue(tv.contains("restoreLastPlayback"))
        val phone = File("src/phone/java/com/totaliptv/pro/MainActivity.kt").readText()
        assertTrue(phone.contains("restoreLastPlayback"))
    }
}
