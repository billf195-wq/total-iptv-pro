package com.totaliptv.pro.ui.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Home shelf sizes for a 1920×1080 Shield.
 *
 * That panel is xhdpi (320 dpi), so the window is 960×540 dp. Three poster
 * rows, including their titles, have to fit in the 540 dp height with no scroll.
 */
object HomeShelfFit {
    const val SHIELD_1080P_HEIGHT_DP = 540

    val desktopContentPadTop = 4.dp
    val desktopContentPadBottom = 4.dp
    val desktopRowGap = 6.dp
    val desktopRowHeader = 16.dp
    val desktopPosterImage = 104.dp
    val desktopPosterTitle = 22.dp

    val classicNav = 40.dp
    val classicLogo = 28.dp
    val classicContentPadTop = 4.dp
    val classicContentPadBottom = 4.dp
    val classicRowGap = 6.dp
    val classicRowHeader = 16.dp
    val classicPosterImage = 100.dp
    val classicPosterTitle = 26.dp

    val sidebarLogo = 36.dp

    fun desktopPoster(): Dp = desktopPosterImage + desktopPosterTitle

    fun desktopRow(): Dp = desktopRowHeader + desktopPoster()

    /** Content column under the removed top bar. The side panel is beside it. */
    fun desktopThreeRows(): Dp =
        desktopContentPadTop + desktopContentPadBottom +
            desktopRow() * 3 + desktopRowGap * 2

    fun classicPoster(): Dp = classicPosterImage + classicPosterTitle

    fun classicRow(): Dp = classicRowHeader + classicPoster()

    /** Nav is one short row. The hero banner is not in this stack. */
    fun classicThreeRows(): Dp =
        classicNav + classicContentPadTop + classicContentPadBottom +
            classicRow() * 3 + classicRowGap * 2

    fun fitsOnShield1080p(): Boolean {
        val limit = SHIELD_1080P_HEIGHT_DP.toFloat()
        return desktopThreeRows().value <= limit && classicThreeRows().value <= limit
    }
}
