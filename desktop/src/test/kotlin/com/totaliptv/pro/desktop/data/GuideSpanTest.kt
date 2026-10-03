package com.totaliptv.pro.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuideSpanTest {

    /** 1920x1080 timeline column after the channel list, in dp. Text scale does not change dp. */
    private val gtrViewportDp = 1400f

    /** 3440x1440 timeline column, in dp. */
    private val ultrawideViewportDp = 3000f

    private val now = 1_700_000_000_000L

    private fun prog(start: Long, end: Long) = EpgProgram(
        id = "$start-$end",
        title = "Show",
        startMs = start,
        endMs = end,
        channelStreamId = 1
    )

    @Test
    fun fixedThreeHourStripDoesNotFillADesktopTimeline() {
        val oldWidth = (30 + 3 * 60) * GuideSpan.DP_PER_MIN
        assertTrue(oldWidth < gtrViewportDp, "old ~3h strip is only ${oldWidth}dp")
        assertTrue(oldWidth < ultrawideViewportDp)
        val gtr = GuideSpan.timeline(now, gtrViewportDp, extended = false)
        assertTrue(gtr.widthDp >= gtrViewportDp)
        assertTrue(gtr.widthDp > oldWidth * 2)
        assertTrue(gtr.aheadMs >= GuideSpan.MIN_AHEAD_MS)
        assertTrue(gtr.aheadMs < GuideSpan.MAX_AHEAD_MS)
        assertEquals(GuideSpan.INITIAL_LISTING_LIMIT, GuideSpan.listingLimit(gtr.aheadMs, extended = false))
    }

    @Test
    fun ultrawideFitsMoreHoursAndStillScrollsTowardADay() {
        val gtr = GuideSpan.timeline(now, gtrViewportDp, extended = false)
        val wide = GuideSpan.timeline(now, ultrawideViewportDp, extended = false)
        assertTrue(wide.aheadMs > gtr.aheadMs)
        assertTrue(wide.aheadMs <= GuideSpan.MAX_AHEAD_MS)
        assertTrue(wide.widthDp >= ultrawideViewportDp)
        assertTrue(GuideSpan.listingLimit(wide.aheadMs, extended = false) > GuideSpan.INITIAL_LISTING_LIMIT)
        assertEquals(wide.aheadMs, GuideSpan.timeline(now, ultrawideViewportDp, extended = false).aheadMs)
    }

    @Test
    fun scrollingRightExtendsAShortScreenToTwentyFourHours() {
        val folded = GuideSpan.timeline(now, gtrViewportDp, extended = false)
        val open = GuideSpan.timeline(now, gtrViewportDp, extended = true)
        assertEquals(GuideSpan.MAX_AHEAD_MS, open.aheadMs)
        assertTrue(open.endMs > folded.endMs)
        assertTrue(open.widthDp > folded.widthDp)
        assertEquals(GuideSpan.EXTENDED_LISTING_LIMIT, GuideSpan.listingLimit(folded.aheadMs, extended = true))
        assertFalse(GuideSpan.shouldExtend(scrollPx = 0, maxScrollPx = 0))
        assertFalse(GuideSpan.shouldExtend(scrollPx = 44, maxScrollPx = 100))
        assertTrue(GuideSpan.shouldExtend(scrollPx = 45, maxScrollPx = 100))
    }

    @Test
    fun shortEpgFallsThroughOnlyWhenTheLongerTableReachesFurther() {
        val short = listOf(prog(now - 10 * 60_000L, now + 2 * 3_600_000L))
        val day = listOf(prog(now - 10 * 60_000L, now + 20 * 3_600_000L))
        assertFalse(GuideSpan.epgReaches(emptyList(), now + GuideSpan.MIN_AHEAD_MS))
        assertFalse(GuideSpan.epgReaches(short, now + GuideSpan.MIN_AHEAD_MS))
        assertTrue(GuideSpan.epgReaches(day, now + GuideSpan.MIN_AHEAD_MS))
        assertEquals(day, GuideSpan.preferLonger(short, day))
        assertEquals(day, GuideSpan.preferLonger(day, short))
        assertEquals(short, GuideSpan.preferLonger(short, null))
        val past = prog(now - 10 * 3_600_000L, now - 8 * 3_600_000L)
        val future = prog(now + 30 * 3_600_000L, now + 31 * 3_600_000L)
        val kept = GuideSpan.withinHorizon(listOf(past, day.first(), future), now)
        assertEquals(listOf(day.first()), kept)
    }

    @Test
    fun guideScreenMeasuresTheStripInsteadOfAFixedThreeHourWindow() {
        val guide = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/GuideScreen.kt").readText()
        assertTrue(guide.contains("GuideSpan.timeline"))
        assertTrue(guide.contains("BoxWithConstraints"))
        assertTrue(guide.contains("GuideSpan.listingLimit"))
        assertTrue(guide.contains("GuideSpan.shouldExtend"))
        assertFalse(guide.contains("3 * 60 * 60_000L"), "timeline must not be hard-coded to 3 hours")
        assertFalse(guide.contains("next 3 hours"))
        assertFalse(guide.contains("visible.take(24)"), "EPG loads for composed rows, not a fixed burst")
    }
}
