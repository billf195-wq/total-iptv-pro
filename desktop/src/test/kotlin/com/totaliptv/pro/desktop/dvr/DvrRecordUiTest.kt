package com.totaliptv.pro.desktop.dvr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val active = entry()
        val look = DvrRecordUi.appearance(active, "ch-1", active.streamUrl)
        assertTrue(look.activeForThisItem)
        assertTrue(look.selected)
        assertEquals(DvrRecordUi.ACTIVE_LABEL, look.label)
    }

    @Test
    fun otherItemStaysIdleWhileSomethingElseRecords() {
        val active = entry()
        val look = DvrRecordUi.appearance(active, "ch-2", "http://host/live/u/p/2.ts")
        assertFalse(look.activeForThisItem)
        assertFalse(look.selected)
        assertEquals(DvrRecordUi.IDLE_LABEL, look.label)
    }

    @Test
    fun finishedRecordingReturnsIdleLook() {
        val done = entry(status = RecordingStatus.COMPLETED.name)
        val look = DvrRecordUi.appearance(done, "ch-1", done.streamUrl)
        assertFalse(look.selected)
        assertEquals(DvrRecordUi.IDLE_LABEL, look.label)
    }

    @Test
    fun matchesUrlIgnoringQuery() {
        assertTrue(
            DvrRecordUi.matches(
                "http://host/movie/u/p/9.mp4?token=a",
                "vod-9",
                "vod-9",
                "http://host/movie/u/p/9.mp4?token=b"
            )
        )
    }

    @Test
    fun desktopRecordControlsUseSharedAppearance() {
        val browse = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/BrowseScreen.kt").readText()
        val guide = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/GuideScreen.kt").readText()
        val overlay = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/SeriesNextOverlay.kt").readText()
        assertTrue(browse.contains("RecordControlButton"), "guide/live/series/movie Record must use shared control")
        assertTrue(guide.contains("RecordControlButton"))
        assertTrue(guide.contains("DvrRecordUi.matches"))
        assertTrue(overlay.contains("recordingThisItem"))
        assertTrue(overlay.contains("Recording…"))
        assertTrue(browse.contains("dvrSnapshot.active"), "sidebar Record must see the active recording")
    }
}
