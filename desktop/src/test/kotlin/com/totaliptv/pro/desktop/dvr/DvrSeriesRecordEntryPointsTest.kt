package com.totaliptv.pro.desktop.dvr

import kotlin.test.Test
import kotlin.test.assertTrue

class DvrSeriesRecordEntryPointsTest {

    @Test
    fun seriesDetailAndEpisodeRowsExposeRecord() {
        val browse = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/BrowseScreen.kt").readText()
        assertTrue(browse.contains("onRecordEpisode"), "series detail must accept Record")
        assertTrue(browse.contains("Record S"), "series detail must have Record SxEy")
        assertTrue(browse.contains("RecordControlButton"), "episode rows must show a Record control")
        assertTrue(
            browse.contains("idleLabelForKind") || browse.contains("IDLE_EPISODE_LABEL"),
            "sidebar while playing a series must offer Record episode"
        )
    }

    @Test
    fun seriesOverlayHasRecord() {
        val overlay = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/SeriesNextOverlay.kt").readText()
        assertTrue(overlay.contains("onRecord"))
        assertTrue(overlay.contains("Text(\"Record\""))
    }
}
