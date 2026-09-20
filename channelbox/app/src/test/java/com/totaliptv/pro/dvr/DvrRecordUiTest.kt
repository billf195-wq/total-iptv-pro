package com.totaliptv.pro.dvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DvrRecordUiTest {

    private fun entry(
        url: String = "http://host/live/u/p/1.ts",
        id: String = "ch-1",
        status: String = RecordingStatus.RECORDING.name
    ) = RecordingEntry(
        id = "rec-1",
        channelName = "CNN",
        title = "News",
        startMs = 1L,
        streamUrl = url,
        channelId = id,
        status = status
    )

    @Test
    fun activeItemStaysSelectedAndLit() {
        val look = DvrRecordUi.appearance(entry(), "ch-1", "http://host/live/u/p/1.ts")
        assertTrue(look.selected)
        assertEquals(DvrRecordUi.ACTIVE_LABEL, look.label)
    }

    @Test
    fun otherItemStaysIdle() {
        val look = DvrRecordUi.appearance(entry(), "ch-2", "http://other")
        assertFalse(look.selected)
        assertEquals(DvrRecordUi.IDLE_LABEL, look.label)
    }

    @Test
    fun androidSurfacesWireActiveState() {
        val tvDetail = File("src/tv/java/com/totaliptv/pro/ui/components/MovieDetailSheet.kt").readText()
        val phoneDetail = File("src/phone/java/com/totaliptv/pro/ui/components/PhoneDetailSheet.kt").readText()
        val player = File("src/main/java/com/totaliptv/pro/ui/player/PlayerActivity.kt").readText()
        val guide = File("src/tv/java/com/totaliptv/pro/ui/epg/EpgGuideScreen.kt").readText()
        val home = File("src/tv/java/com/totaliptv/pro/ui/home/HomeScreen.kt").readText()
        val phoneRow = File("src/phone/java/com/totaliptv/pro/ui/components/PhoneComponents.kt").readText()
        assertTrue(tvDetail.contains("recordLook.selected"))
        assertTrue(phoneDetail.contains("recordLook.selected"))
        assertTrue(player.contains("refreshRecordButton"))
        assertTrue(guide.contains("recordLook.selected"))
        assertTrue(home.contains("recordActive"))
        assertTrue(phoneRow.contains("recordActive"))
    }
}
