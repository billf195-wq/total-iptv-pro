package com.totaliptv.pro.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LastEpisodeBannerTest {

    private fun ep(season: Int, num: Int, id: String = "$season-$num"): SeriesEpisode =
        SeriesEpisode(
            id = id,
            title = "E$num",
            season = season,
            episodeNum = num,
            streamUrl = "http://host/series/u/p/$id.mp4"
        )

    @Test
    fun unknownPositionIsNotLastEpisode() {
        val eps = listOf(ep(1, 1), ep(1, 2), ep(1, 3))
        assertFalse(LastEpisodeBanner.isKnownLastEpisode(eps, 9, 9, "missing", "http://nope"))
        assertEquals(
            LastEpisodeBanner.Mode.HIDDEN,
            LastEpisodeBanner.overlayMode(eps, 9, 9, "missing", "http://nope")
        )
        assertEquals(LastEpisodeBanner.Mode.HIDDEN, LastEpisodeBanner.overlayMode(emptyList(), 1, 1, "1-1"))
    }

    @Test
    fun finaleShowsBrieflyThenMustNotStay() {
        val eps = listOf(ep(1, 1), ep(1, 2))
        assertTrue(LastEpisodeBanner.isKnownLastEpisode(eps, 1, 2, "1-2"))
        assertTrue(LastEpisodeBanner.shouldShowBriefly(eps, 1, 2, "1-2"))
        assertFalse(LastEpisodeBanner.shouldStayVisible())
        assertEquals(4_000L, LastEpisodeBanner.AUTO_DISMISS_MS)
        assertEquals(
            LastEpisodeBanner.Mode.LAST_BRIEF,
            LastEpisodeBanner.overlayMode(eps, 1, 2, "1-2", eps[1].streamUrl)
        )
    }

    @Test
    fun midSeriesShowsNextNotLastBanner() {
        val eps = listOf(ep(1, 1), ep(1, 2), ep(1, 3))
        assertFalse(LastEpisodeBanner.isKnownLastEpisode(eps, 1, 1, "1-1"))
        assertEquals(
            LastEpisodeBanner.Mode.NEXT,
            LastEpisodeBanner.overlayMode(eps, 1, 1, "1-1", eps[0].streamUrl)
        )
    }

    @Test
    fun lastBannerAutoDismissesOnSharedClockWindowsAndLinux() {
        val shown = 1_000L
        assertTrue(LastEpisodeBanner.overlayStillVisible(LastEpisodeBanner.Mode.LAST_BRIEF, shown, shown + 3_999))
        assertFalse(LastEpisodeBanner.overlayStillVisible(LastEpisodeBanner.Mode.LAST_BRIEF, shown, shown + 4_000))
        assertFalse(
            LastEpisodeBanner.overlayStillVisible(
                LastEpisodeBanner.Mode.LAST_BRIEF,
                shown,
                shown + 1,
                dismissedKey = "s|1|u",
                key = "s|1|u"
            )
        )
        assertTrue(
            LastEpisodeBanner.overlayStillVisible(LastEpisodeBanner.Mode.NEXT, shown, shown + 5_999)
        )
        assertFalse(
            LastEpisodeBanner.overlayStillVisible(LastEpisodeBanner.Mode.NEXT, shown, shown + 6_000)
        )
        assertEquals(LastEpisodeBanner.Reveal.INTRO, LastEpisodeBanner.reveal(
            LastEpisodeBanner.Mode.NEXT, shown, shown + 1_000, null, null, null, false
        ))
        assertEquals(LastEpisodeBanner.Reveal.HIDDEN, LastEpisodeBanner.reveal(
            LastEpisodeBanner.Mode.NEXT, shown, shown + 6_000, null, 30_000, 2_400_000, false
        ))
        assertEquals(LastEpisodeBanner.Reveal.ENDING, LastEpisodeBanner.reveal(
            LastEpisodeBanner.Mode.NEXT, shown, shown + 60_000, null, 2_360_000, 2_400_000, false
        ))
        assertEquals(LastEpisodeBanner.Reveal.MOUSE, LastEpisodeBanner.reveal(
            LastEpisodeBanner.Mode.NEXT, shown, shown + 60_000, shown + 58_000, null, null, true
        ))
        assertEquals(LastEpisodeBanner.Reveal.HIDDEN, LastEpisodeBanner.reveal(
            LastEpisodeBanner.Mode.NEXT, shown, shown + 60_000, shown + 50_000, null, null, true
        ))
        assertFalse(
            LastEpisodeBanner.overlayStillVisible(
                LastEpisodeBanner.Mode.NEXT,
                shown,
                shown + 1_000,
                dismissedKey = "s|1|u",
                key = "s|1|u"
            )
        )
        assertFalse(
            LastEpisodeBanner.shouldShow(
                LastEpisodeBanner.Mode.NEXT,
                shown,
                shown + 1_000,
                dismissedKey = null,
                key = "s|1|u",
                sawPlayer = true,
                playerRunning = false
            )
        )
        assertTrue(
            LastEpisodeBanner.shouldShow(
                LastEpisodeBanner.Mode.NEXT,
                shown,
                shown + 1_000,
                dismissedKey = null,
                key = "s|1|u",
                sawPlayer = false,
                playerRunning = false
            )
        )
        val line = LastEpisodeBanner.logLine("hide", LastEpisodeBanner.Mode.NEXT, "timeout", "1-2", 1920, 0)
        assertEquals("banner hide mode=NEXT reason=timeout episodeId=1-2 monitor=1920,0", line)
        assertFalse(line.contains("http"))
        assertFalse(
            LastEpisodeBanner.overlayStillVisible(LastEpisodeBanner.Mode.HIDDEN, shown, shown)
        )
    }

    @Test
    fun windowsHostUsesTheSameDismissPredicate() {
        val host = com.totaliptv.pro.desktop.ui.SeriesNextHost()
        val play = com.totaliptv.pro.desktop.ui.ActiveSeriesPlay(
            episodes = listOf(ep(1, 1), ep(1, 2)),
            seriesName = "GTR",
            seriesId = 9,
            current = ep(1, 2)
        )
        assertTrue(host.shouldKeepOverlay(play, nowMs = 100, firstShownAtMs = 100, dismissedKey = null))
        assertFalse(host.shouldKeepOverlay(play, nowMs = 100 + 4_000, firstShownAtMs = 100, dismissedKey = null))
        val overlay = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/SeriesNextOverlay.kt").readText()
        val root = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/AppRoot.kt").readText()
        assertTrue(overlay.contains("LastEpisodeBanner.overlayStillVisible"))
        assertTrue(overlay.contains("nowMs: Long"))
        val dismissBlock = overlay.substringAfter("fun sync(").substringBefore("fun dismissLastIfMatching")
        assertFalse(dismissBlock.contains("AppPaths.isWindows"), "Windows must not fork dismiss logic")
        assertTrue(root.contains("setSeriesSession(null)"))
        assertTrue(root.contains("LastEpisodeBanner.AUTO_DISMISS_MS"))
        assertTrue(overlay.contains("LastEpisodeBanner.reveal"))
        assertTrue(overlay.contains("INTRO_SHOW_MS") || overlay.contains("reveal("))
        assertTrue(overlay.contains("player-exited"))
        assertTrue(overlay.contains("sampleMouse"))
        assertEquals(6_000L, LastEpisodeBanner.INTRO_SHOW_MS)
        assertEquals(5_000L, LastEpisodeBanner.MOUSE_HIDE_MS)
        assertEquals(60_000L, LastEpisodeBanner.ENDING_WINDOW_MS)
        assertTrue(overlay.contains("WindowPositioner.playbackMonitor()"))
        assertFalse(overlay.contains("Mode.NEXT) {\n            resetLastBrief()"))
        val (x, y) = com.totaliptv.pro.desktop.ui.overlayOrigin(
            monitorX = 1920,
            monitorY = 0,
            monitorWidth = 1920,
            monitorHeight = 1080,
            overlayWidth = 420,
            overlayHeight = 196,
            marginPx = 80
        )
        assertEquals(1920 + (1920 - 420) / 2, x)
        assertEquals(1080 - 196 - 80, y)
        assertTrue(x >= 1920, "banner must sit on the player monitor, not the primary")
        val main = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/Main.kt").readText()
        assertTrue(main.contains("seriesNextHost.dismiss(\"esc\")"))
        host.clear()
    }
}
