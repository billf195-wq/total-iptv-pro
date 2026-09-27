package com.totaliptv.pro.ui.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Home shelf sizes for a 1920×1080 Shield.
 *
 * That panel is xhdpi (320 dpi), so the window is 960×540 dp. Three poster
 * rows, including their titles, have to fit in the 540 dp height with no scroll.
 */
object HomeShelfFit {
    const val SHIELD_1080P_HEIGHT_DP = 540

    /**
     * Top of every desktop page (Home, Live, Movies, Series, TV Guide,
     * Favorites, Recordings, Settings). No banner sits above this.
     */
    val pageTopOffset = 4.dp
    val desktopContentPadTop = pageTopOffset
    val desktopContentPadBottom = 4.dp
    val desktopRowGap = 6.dp
    val desktopRowHeader = 16.dp
    val desktopPosterImage = 104.dp
    val desktopPosterTitle = 22.dp

    /** Phone page header. Content on every phone page starts under this bar. */
    val phoneTopOffset = 40.dp
    val classicNav = phoneTopOffset
    val classicLogo = 28.dp
    val classicContentPadTop = 4.dp
    val classicContentPadBottom = 4.dp
    val classicRowGap = 6.dp
    val classicRowHeader = 16.dp
    val classicPosterImage = 100.dp
    val classicPosterTitle = 26.dp

    val sidebarLogo = 36.dp

    /**
     * "Total IPTV Pro" in semi-bold is about 7 em wide. The desktop side panel
     * is 220×0.70 dp with 16×0.70 dp padding on each side (131.6 dp inside).
     * 18 sp × 7 em = 126 dp, so the name fits on one line. 20 sp would be 140 dp
     * and would clip.
     */
    val brandNameSp = 18.sp
    const val BRAND_NAME_EM = 7.0f

    fun sidebarBrandInnerWidthDp(): Float {
        val panel = 220f * 0.70f
        val pad = 16f * 0.70f
        return panel - pad * 2f
    }

    fun brandNameFitsSidebar(): Boolean =
        brandNameSp.value * BRAND_NAME_EM <= sidebarBrandInnerWidthDp()

    /**
     * Catalog search field on Live, Movies, Series, and Search.
     * 48 dp with 8 dp vertical padding leaves 32 dp for a 16 sp line, so the
     * placeholder is not clipped.
     */
    val searchFieldHeight = 48.dp
    val searchFieldPadH = 14.dp
    val searchFieldPadV = 8.dp
    val searchFieldCorner = 8.dp

    fun desktopPoster(): Dp = desktopPosterImage + desktopPosterTitle

    fun desktopRow(): Dp = desktopRowHeader + desktopPoster()

    /** Three titled rows and the gaps between them, without page padding. */
    fun desktopHomeBlock(): Dp = desktopRow() * 3 + desktopRowGap * 2

    /**
     * Desktop Home content column is the full 540 dp window (the side panel
     * sits beside it). Center that block so the leftover is equal above the
     * first row and below the third.
     */
    fun desktopHomeTopOffset(): Dp =
        centeredTopOffset(SHIELD_1080P_HEIGHT_DP.dp, desktopHomeBlock(), pageTopOffset)

    /** Content column under the removed top bar. The side panel is beside it. */
    fun desktopThreeRows(): Dp =
        desktopContentPadTop + desktopContentPadBottom +
            desktopRow() * 3 + desktopRowGap * 2

    fun classicPoster(): Dp = classicPosterImage + classicPosterTitle

    fun classicRow(): Dp = classicRowHeader + classicPoster()

    /** Area under the classic nav on a 1080p Shield. */
    fun classicHomeArea(): Dp = SHIELD_1080P_HEIGHT_DP.dp - classicNav

    fun classicHomeBlock(): Dp = classicRow() * 3 + classicRowGap * 2

    /** Gap under the nav when three classic Home rows are centered. */
    fun classicHomeTopOffset(): Dp =
        centeredTopOffset(classicHomeArea(), classicHomeBlock(), classicContentPadTop)

    /**
     * Top inset that centers [block] in [area]. When the block is taller than
     * the area, Home scrolls and this returns [topWhenTaller] so the first row
     * starts at the shared page offset.
     */
    fun centeredTopOffset(area: Dp, block: Dp, topWhenTaller: Dp): Dp {
        if (block <= 0.dp || block > area) return topWhenTaller
        return (area - block) / 2
    }

    fun homeBlock(row: Dp, gap: Dp, rows: Int): Dp {
        if (rows <= 0) return 0.dp
        return row * rows + gap * (rows - 1)
    }

    /** Nav is one short row. The hero banner is not in this stack. */
    fun classicThreeRows(): Dp =
        classicNav + classicContentPadTop + classicContentPadBottom +
            classicRow() * 3 + classicRowGap * 2

    fun fitsOnShield1080p(): Boolean {
        val limit = SHIELD_1080P_HEIGHT_DP.toFloat()
        return desktopThreeRows().value <= limit && classicThreeRows().value <= limit
    }
}
