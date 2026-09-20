package com.totaliptv.pro.ui.desktop

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.totaliptv.pro.ui.theme.AccentPreset
import com.totaliptv.pro.ui.theme.AppearanceMode
import com.totaliptv.pro.ui.theme.LocalTipColors
import com.totaliptv.pro.ui.theme.tipColorsFor

// Fallbacks for non-composable defaults (pre-theme). Prefer @Composable getters below.
val TipAmberFallback = Color(0xFFFFB300)
val TipAccentFallback = Color(0xFFFFD54F)
val TipGoldTextFallback = Color(0xFFFFE082)
val TipGoldMutedFallback = Color(0xFFC9A84C)
val TipOnAmberFallback = Color(0xFF1A1200)
val TipBgFallback = Color(0xFF0B0F14)
val TipSurfaceFallback = Color(0xFF141A22)
val TipSurfaceAltFallback = Color(0xFF1C2430)
val TipOutlineFallback = Color(0xFF2A3544)

val TipAmber: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.brand

val TipAmberDark: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.brandSoft

val TipAccent: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.focusBorder

val TipGoldText: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.onCinema

val TipGoldMuted: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.onCinemaMuted

val TipOnAmber: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalTipColors.current.isDark) Color(0xFF1A1200) else Color.White

val TipBg: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.cinemaBg

val TipSurface: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.cinemaSurface

val TipSurfaceAlt: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.cinemaSurfaceHigh

val TipOutline: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.border

object TipDimens {
    const val Scale = 0.70f
    const val PosterScale = 0.60f

    fun dp(desktop: Number): Dp = (desktop.toFloat() * Scale).dp
    fun sp(desktop: Number): TextUnit = (desktop.toFloat() * Scale).sp
    private fun posterDp(desktop: Number): Dp = (desktop.toFloat() * PosterScale).dp

    val PosterWidth = posterDp(140)
    val PosterPad = posterDp(8)
    val PosterCorner = posterDp(8)
    val PosterTitleSp = sp(13)
    val PosterPlaceholderSp = sp(28)
    val PosterRowGap = posterDp(12)
    val PosterGridGap = posterDp(12)

    val SidebarWidth = dp(220)
    // Taller banner so app_banner logo does not clip/overlap sidebar chrome
    val BannerHeight = dp(120)
    val ContentPad = dp(20)
    val SidebarPad = dp(16)
    val NavItemPadH = dp(12)
    val NavItemPadV = dp(12)
    val NavGap = dp(8)
    val NavCorner = dp(10)

    val LiveThumb = dp(44)
    val LivePadH = dp(14)
    val LivePadV = dp(10)

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

    val ChipPadH = dp(12)
    val ChipPadV = dp(8)
    val ChipCorner = dp(16)
    val ButtonPadH = dp(20)
    val ButtonPadV = dp(12)
    val FocusBorder = dp(2)
}

@Composable
fun DesktopTipTheme(
    appearance: AppearanceMode = AppearanceMode.DARK,
    accent: AccentPreset = AccentPreset.AMBER,
    content: @Composable () -> Unit
) {
    val tip = remember(appearance, accent) { tipColorsFor(appearance, accent) }
    val onPrimary = if (tip.isDark) Color(0xFF1A1200) else Color.White
    val scheme = if (tip.isDark) {
        darkColorScheme(
            primary = tip.brand,
            onPrimary = onPrimary,
            secondary = tip.focusBorder,
            onSecondary = onPrimary,
            background = tip.cinemaBg,
            onBackground = tip.onCinema,
            surface = tip.cinemaSurface,
            onSurface = tip.onCinema,
            surfaceVariant = tip.cinemaSurfaceHigh,
            onSurfaceVariant = tip.onCinemaMuted,
            outline = tip.border
        )
    } else {
        lightColorScheme(
            primary = tip.brand,
            onPrimary = onPrimary,
            secondary = tip.focusBorder,
            onSecondary = onPrimary,
            background = tip.cinemaBg,
            onBackground = tip.onCinema,
            surface = tip.cinemaSurface,
            onSurface = tip.onCinema,
            surfaceVariant = tip.cinemaSurfaceHigh,
            onSurfaceVariant = tip.onCinemaMuted,
            outline = tip.border
        )
    }
    val typography = Typography(
        headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = TipDimens.HeadlineLargeSp, color = tip.onCinema),
        headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = TipDimens.HeadlineMediumSp, color = tip.onCinema),
        titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = TipDimens.TitleLargeSp, color = tip.onCinema),
        titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = TipDimens.TitleMediumSp, color = tip.onCinema),
        bodyLarge = TextStyle(fontSize = TipDimens.BodyLargeSp, color = tip.onCinema),
        bodyMedium = TextStyle(fontSize = TipDimens.BodyMediumSp, color = tip.onCinema),
        labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = TipDimens.LabelLargeSp, color = tip.onCinema)
    )
    CompositionLocalProvider(
        LocalTipColors provides tip,
        LocalContentColor provides tip.onCinema
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
