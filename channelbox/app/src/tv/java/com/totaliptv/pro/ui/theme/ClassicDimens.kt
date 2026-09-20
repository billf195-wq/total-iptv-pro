package com.totaliptv.pro.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Classic (leanback) layout tokens — denser cards vs stock TV sizes.
 * Desktop uses [com.totaliptv.pro.ui.desktop.TipDimens] and must stay independent.
 *
 * Scale stays at 1.4.29 (0.88) for fonts via Theme density.
 * Poster/hero/grid bases reduced another ~12% in 1.4.30 (banners, not type).
 * Category left rail thinned in 1.4.31 (Home 210 / Browse 268 -> 168).
 */
object ClassicDimens {
    /** Font / density scale — unchanged from 1.4.29. */
    const val Scale = 0.88f

    fun dp(base: Number): Dp = (base.toFloat() * Scale).dp
    fun sp(base: Number): TextUnit = (base.toFloat() * Scale).sp

    /** Left category rail width (Classic TV only). Desktop TipDimens.SidebarWidth unchanged. */
    val CategoryRailWidth = 168.dp

    // Poster / hero / grid (~12% smaller bases vs 1.4.29: 118/156/200/120/110/160/200)
    val PosterWidth = dp(103)
    val ChannelCardWidth = dp(137)
    val HeroHeight = dp(175)
    val HeroEmptyHeight = dp(180)
    val PosterGridMin = dp(96)
    val VodGridMin = dp(140)
    val ChannelGridMin = dp(175)
    val PosterRowGap = dp(7)
    // Chip / nav padding left at 1.4.29 levels
    val ChipPadH = dp(10)
    val ChipPadV = dp(6)
    val NavPillPadH = dp(14)
    val NavPillPadV = dp(6)
    val SortChipPadH = dp(8)
    val SortChipPadV = dp(4)
    val SectionTitlePadH = dp(28)
    val SectionTitlePadV = dp(10)
}
