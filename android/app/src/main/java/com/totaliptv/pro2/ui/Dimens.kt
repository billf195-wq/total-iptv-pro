package com.totaliptv.pro2.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TV UI sizes vs Ubuntu desktop Compose dp/sp.
 *
 * Television_1080p @ ~320dpi makes raw desktop dp look oversized. Fonts use
 * [Scale] (Bill likes these). Posters/banner use a slightly tighter
 * [PosterScale] (~15% smaller than the font-scaled chrome).
 */
object TipDimens {
    /** Fonts / general chrome vs desktop baseline. */
    const val Scale = 0.70f

    /**
     * Posters + top banner only — modest extra shrink (~15% vs Scale sizes).
     * 140×0.60=84.dp, 120×0.60=72.dp.
     */
    const val PosterScale = 0.60f

    fun dp(desktop: Number): Dp = (desktop.toFloat() * Scale).dp
    fun sp(desktop: Number): TextUnit = (desktop.toFloat() * Scale).sp
    private fun posterDp(desktop: Number): Dp = (desktop.toFloat() * PosterScale).dp

    // Posters (desktop 140×2:3) — modestly smaller than font Scale
    val PosterWidth = posterDp(140)       // 84.dp (was 98.dp)
    val PosterPad = posterDp(8)           // 4.8.dp
    val PosterCorner = posterDp(8)
    val PosterTitleSp = sp(13)            // fonts unchanged
    val PosterPlaceholderSp = sp(28)
    val PosterRowGap = posterDp(12)       // 7.2.dp
    val PosterGridGap = posterDp(12)

    // Chrome — banner uses PosterScale; sidebar/nav keep font Scale
    val SidebarWidth = dp(220)            // 154.dp
    val BannerHeight = posterDp(120)      // 72.dp (was 84.dp)
    val ContentPad = dp(20)
    val SidebarPad = dp(16)
    val NavItemPadH = dp(12)
    val NavItemPadV = dp(12)
    val NavGap = dp(8)
    val NavCorner = dp(10)

    // Live row
    val LiveThumb = dp(44)
    val LivePadH = dp(14)
    val LivePadV = dp(10)

    // Type — leave alone (Bill: fonts are great)
    val HeadlineLargeSp = sp(32)
    val HeadlineMediumSp = sp(24)
    val TitleLargeSp = sp(20)
    val TitleMediumSp = sp(16)
    val BodyLargeSp = sp(15)
    val BodyMediumSp = sp(13)
    val LabelLargeSp = sp(14)
    val ChipSp = sp(13)
    val BrandSp = sp(15)
    val SubBrandSp = sp(13)

    // Chips / buttons
    val ChipPadH = dp(12)
    val ChipPadV = dp(8)
    val ChipCorner = dp(16)
    val ButtonPadH = dp(20)
    val ButtonPadV = dp(12)
    val FocusBorder = dp(2)
}
