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
            LastEpisodeBanner.overlayStillVisible(LastEpisodeBanner.Mode.NEXT, shown, shown + 60_000)
        )
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
        assertTrue(root.contains("dismissLastIfMatching"))
        assertTrue(root.contains("LastEpisodeBanner.AUTO_DISMISS_MS"))
        host.clear()
    }
}
