package com.totaliptv.pro.desktop.dvr

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DvrStartPolicyTest {

    @Test
    fun onlyRecordButtonOrDueScheduleMayStart() {
        assertTrue(DvrStartPolicy.allowsImmediateStart(DvrStartReason.USER_RECORD))
        assertTrue(DvrStartPolicy.allowsImmediateStart(DvrStartReason.SCHEDULE_DUE))
    }

    @Test
    fun openingBrowsingOrPlayingMustNotStart() {
        assertTrue(DvrStartPolicy.isAutoPlaybackEvent(DvrStartPolicy.OPEN_VOD))
        assertTrue(DvrStartPolicy.isAutoPlaybackEvent(DvrStartPolicy.PLAY_VOD))
        assertTrue(DvrStartPolicy.isAutoPlaybackEvent(DvrStartPolicy.PLAY_ITEM))
        assertTrue(DvrStartPolicy.isAutoPlaybackEvent(DvrStartPolicy.OPEN_SERIES))
        assertTrue(DvrStartPolicy.isAutoPlaybackEvent(DvrStartPolicy.PLAY_SERIES))
        assertTrue(DvrStartPolicy.isAutoPlaybackEvent(DvrStartPolicy.BROWSE))
        assertFalse(DvrStartPolicy.isAutoPlaybackEvent("user_record_button"))
    }

    @Test
    fun playAndOpenVodNeverCallStartNow() {
        val appRoot = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/AppRoot.kt").readText()
        val playItem = appRoot.substringAfter("fun playItem(").substringBefore("fun skipToNextEpisode(")
        val openVod = appRoot.substringAfter("fun openVod(").substringBefore("fun toggleFavorite(")
        val openSeries = appRoot.substringAfter("fun openSeries(").substringBefore("fun openVod(")
        assertFalse(playItem.contains("DvrRecorder.startNow"), "play must not start a recording")
        assertFalse(openVod.contains("DvrRecorder.startNow"), "opening a movie must not start a recording")
        assertFalse(openSeries.contains("DvrRecorder.startNow"), "opening a series must not start a recording")
        assertTrue(appRoot.contains("DvrStartReason.USER_RECORD"))
    }
}
