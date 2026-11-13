package com.totaliptv.pro.desktop

import com.totaliptv.pro.desktop.WindowFullscreen.EscAction
import com.totaliptv.pro.desktop.data.SavedPrefs
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowFullscreenTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun startFullscreenIsOnByDefault() = assertTrue(SavedPrefs().startFullscreen)

    @Test fun oldPrefsFileWithoutTheKeyStartsFullscreenAndKeepsPlayerSetting() {
        val p = json.decodeFromString<SavedPrefs>("""{"openPlayerFullscreen":false,"windowMaximized":true}""")
        assertTrue(p.startFullscreen)
        assertFalse(p.openPlayerFullscreen)
    }

    @Test fun savedOffSurvivesRoundTrip() {
        val p = json.decodeFromString<SavedPrefs>(json.encodeToString(SavedPrefs.serializer(), SavedPrefs(startFullscreen = false)))
        assertFalse(p.startFullscreen)
        assertTrue(p.openPlayerFullscreen)
    }

    @Test fun escLeavesFullscreenWhenIdle() =
        assertEquals(EscAction.LEAVE_FULLSCREEN, WindowFullscreen.escAction(fullscreen = true, bannerShowing = false, splitActive = false, playing = false))

    @Test fun escDuringPlaybackDoesNotLeaveFullscreen() =
        assertEquals(EscAction.PASS_TO_PLAYER, WindowFullscreen.escAction(fullscreen = true, bannerShowing = false, splitActive = false, playing = true))

    @Test fun escKeepsOldJobsForBannerAndSplit() {
        assertEquals(EscAction.DISMISS_BANNER, WindowFullscreen.escAction(true, bannerShowing = true, splitActive = true, playing = true))
        assertEquals(EscAction.STOP_SPLIT, WindowFullscreen.escAction(true, bannerShowing = false, splitActive = true, playing = true))
    }

    @Test fun escWhenWindowedDoesNothing() =
        assertEquals(EscAction.NONE, WindowFullscreen.escAction(fullscreen = false, bannerShowing = false, splitActive = false, playing = false))

    @Test fun f11Toggles() { assertFalse(WindowFullscreen.toggled(true)); assertTrue(WindowFullscreen.toggled(false)) }
}
