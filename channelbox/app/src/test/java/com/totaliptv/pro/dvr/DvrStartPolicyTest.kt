package com.totaliptv.pro.dvr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DvrStartPolicyTest {
    @Test
    fun onlyRecordButtonOrDueScheduleMayStart() {
        assertTrue(DvrStartPolicy.allowsImmediateStart(DvrStartReason.USER_RECORD))
        assertTrue(DvrStartPolicy.allowsImmediateStart(DvrStartReason.SCHEDULE_DUE))
        assertTrue(DvrStartPolicy.isAutoPlaybackEvent(DvrStartPolicy.OPEN_VOD))
        assertTrue(DvrStartPolicy.isAutoPlaybackEvent(DvrStartPolicy.PLAY_VOD))
        assertFalse(DvrStartPolicy.isAutoPlaybackEvent("user_record_button"))
    }

    @Test
    fun playerDoesNotAutoRecordOnStart() {
        val player = File("src/main/java/com/totaliptv/pro/ui/player/PlayerActivity.kt").readText()
        val beforeRecordButton = player.substringBefore("controls.addView(controlBtn")
        assertFalse(beforeRecordButton.contains("DvrActions.recordNow"))
        assertTrue(player.contains("Record episode"))
    }

    @Test
    fun seriesDetailExposesRecordEpisode() {
        val tv = File("src/tv/java/com/totaliptv/pro/ui/components/MovieDetailSheet.kt").readText()
        val phone = File("src/phone/java/com/totaliptv/pro/ui/components/PhoneDetailSheet.kt").readText()
        assertTrue(tv.contains("IDLE_EPISODE_LABEL") || tv.contains("Record episode"))
        assertTrue(phone.contains("IDLE_EPISODE_LABEL") || phone.contains("Record episode"))
    }
}
