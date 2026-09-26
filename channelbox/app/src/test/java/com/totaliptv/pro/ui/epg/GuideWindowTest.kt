package com.totaliptv.pro.ui.epg

import com.totaliptv.pro.data.model.EpgProgram
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuideWindowTest {

    @Test
    fun hoursFillTheTimelineAndKeepACappedLookahead() {
        // 1080p Shield timeline is roughly 760dp. At 120dp/hour that is more than a 4-hour strip.
        assertEquals(7, GuideWindow.visibleHours(760f, 120f))
        assertEquals(15, GuideWindow.totalHours(760f, 120f))
        // The old 220dp columns on an 880dp strip were exactly four hours.
        assertEquals(4, GuideWindow.visibleHours(880f, 220f))
        assertEquals(12, GuideWindow.totalHours(880f, 220f))
        assertEquals(1, GuideWindow.visibleHours(10f, 120f))
        assertEquals(GuideWindow.MAX_HOURS, GuideWindow.visibleHours(5_000f, 120f))
        assertEquals(GuideWindow.MAX_HOURS, GuideWindow.totalHours(5_000f, 120f))
        assertTrue(GuideWindow.LISTING_LIMIT <= GuideWindow.MAX_HOURS * 2 + 2)
        assertTrue(GuideWindow.LISTING_LIMIT >= GuideWindow.MAX_HOURS)
    }

    @Test
    fun snapStartSitsOnThePreviousHalfHour() {
        val half = 30L * 60L * 1000L
        val now = 10 * half + 1_000L
        assertEquals(9 * half, GuideWindow.snapStart(now))
        assertEquals(0L, GuideWindow.snapStart(0L))
        val start = GuideWindow.snapStart(now)
        assertEquals(start + 6 * 60L * 60L * 1000L, GuideWindow.windowEndMs(start, 6))
    }

    @Test
    fun retainKeepsOnlyProgramsThatOverlapTheHorizon() {
        val start = 1_000_000L
        val end = start + 2 * 60L * 60L * 1000L
        val programs = listOf(
            prog("before", start - 2 * 60L * 60L * 1000L, start - 1_000L),
            prog("overlap", start - 10_000L, start + 30_000L),
            prog("inside", start + 60_000L, start + 120_000L),
            prog("after", end + 1_000L, end + 60_000L)
        )
        val kept = GuideWindow.retain(programs, start, end)
        assertEquals(listOf("overlap", "inside"), kept.map { it.title })
    }

    @Test
    fun guideUsesScreenWidthAndDoesNotLockFourHours() {
        val guide = File("src/tv/java/com/totaliptv/pro/ui/epg/EpgGuideScreen.kt").readText()
        val repo = File("src/main/java/com/totaliptv/pro/data/repo/CatalogRepository.kt").readText()
        assertTrue(guide.contains("GuideWindow.totalHours"))
        assertTrue(guide.contains("screenWidthDp"))
        assertFalse(guide.contains("WINDOW_HOURS"))
        assertFalse(guide.contains("220.dp"))
        assertTrue(repo.contains("GuideWindow.LISTING_LIMIT"))
        assertTrue(repo.contains("GuideWindow.retain"))
        assertFalse(repo.contains("limit = 10"))
        assertTrue(File("src/phone").walk().none { file ->
            file.isFile && file.extension == "kt" && file.readText().contains("EpgGuideScreen")
        })
    }

    private fun prog(title: String, startMs: Long, endMs: Long) = EpgProgram(
        title = title,
        startMs = startMs,
        endMs = endMs
    )
}
