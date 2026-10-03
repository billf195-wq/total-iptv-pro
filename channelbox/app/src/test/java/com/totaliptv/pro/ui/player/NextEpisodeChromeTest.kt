package com.totaliptv.pro.ui.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TV and phone share [PlayerActivity]. The next-episode banner must use small
 * text and a content-sized height so large font scale, a short phone, and TV
 * overscan cannot clip the bottom of the label.
 */
class NextEpisodeChromeTest {

    @Test
    fun overscanAndMessageLeaveRoomForTheButtons() {
        assertEquals(54, NextEpisodeChrome.overscanPx(1080))
        assertEquals(0, NextEpisodeChrome.overscanPx(0))
        assertEquals(972, NextEpisodeChrome.overlayMaxHeightPx(1080, 54))
        val messageMax = NextEpisodeChrome.messageMaxHeightPx(
            screenHeightPx = 320,
            overscanPx = 16,
            scaledDensity = 2f
        )
        val safe = NextEpisodeChrome.overlayMaxHeightPx(320, 16)
        assertTrue(messageMax < safe)
        assertTrue(messageMax > 0)
        assertEquals(16, NextEpisodeChrome.verticalPaddingPx(2f))
        assertTrue(NextEpisodeChrome.BUTTON_TEXT_SP <= 12f)
        assertTrue(NextEpisodeChrome.DIALOG_TITLE_SP <= 16f)
        assertTrue(NextEpisodeChrome.DIALOG_MESSAGE_SP <= 13f)
    }

    @Test
    fun sharedPlayerSizesNextEpisodeToContent() {
        val player = File("src/main/java/com/totaliptv/pro/ui/player/PlayerActivity.kt").readText()
        assertTrue(player.contains("applyContentSizedButton"))
        assertTrue(player.contains("NextEpisodeChrome.BUTTON_TEXT_SP"))
        assertTrue(player.contains("minimumHeight = 0"))
        assertTrue(player.contains("WRAP_CONTENT"))
        assertTrue(player.contains("NextEpisodeOverlayHost"))
        assertTrue(player.contains("NextEpisodeMessageScroll"))
        assertTrue(player.contains("HorizontalScrollView"))
        assertFalse(player.contains("setMessage(\"Play next?"))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains(".ui.player.PlayerActivity"))
        assertTrue(File("src/phone/AndroidManifest.xml").readText().contains(".ui.player.PlayerActivity"))
    }
}
