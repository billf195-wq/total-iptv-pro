package com.totaliptv.pro.desktop.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

// Amber / gold accents (dark + light themes)
val TipBlue = Color(0xFFFFB300)      // primary amber/gold (name kept for call sites)
val TipBlueDark = Color(0xFFFF8F00)  // deeper amber
val TipAccent = Color(0xFFFFD54F)    // light gold highlights

/** Readable gold body/title text on dark UI (replaces black / near-black). */
val TipGoldText = Color(0xFFFFE082)
/** Softer gold for secondary / muted labels on dark. */
val TipGoldMuted = Color(0xFFC9A84C)
/** Dark ink on solid amber filled buttons / selected chips (high contrast). */
val TipOnAmber = Color(0xFF1A1200)
/** Filled Record control while this item is recording / downloading. */
val TipRecordActive = Color(0xFFE53935)
val TipOnRecordActive = Color(0xFFFFFFFF)

// Sidebar, banner, and cards. Unchanged so they stay a step lighter than the TV screen.
val DarkTipBg = Color(0xFF0B0F14)
val DarkTipSurface = Color(0xFF141A22)
val DarkTipSurfaceAlt = Color(0xFF1C2430)

/**
 * Piano-black panel behind Live TV, Movies, and Series.
 * A short lighter band at the top fades into near-pure black.
 */
val TipContentSheen = Color(0xFF161616)
val TipContentSheenFade = Color(0xFF101010)
val TipContentBlack = Color(0xFF0A0A0A)
val TipContentBlackMid = Color(0xFF060606)
val TipContentBlackBottom = Color(0xFF050505)

// Mutable palette driven by theme mode (defaults = dark + gold type)
var TipBg = DarkTipBg
    private set
var TipSurface = DarkTipSurface
    private set
var TipSurfaceAlt = DarkTipSurfaceAlt
    private set
var TipOnBg = TipGoldText
    private set
var TipMuted = TipGoldMuted
    private set
var tipContentGloss = true
    private set

/** Color stops for the browsing panel. Light theme stays a flat page color so dark text remains readable. */
fun tvContentColorStops(darkTheme: Boolean): List<Pair<Float, Color>> {
    if (!darkTheme) {
        val page = Color(0xFFF4F7FB)
        return listOf(0f to page, 1f to page)
    }
    return listOf(
        0.00f to TipContentSheen,
        0.07f to TipContentSheenFade,
        0.18f to TipContentBlack,
        0.42f to TipContentBlackMid,
        1.00f to TipContentBlackBottom
    )
}

fun tvContentBrush(darkTheme: Boolean = tipContentGloss): Brush {
    val stops = tvContentColorStops(darkTheme)
    val colors = stops.map { it.second }.distinct()
    if (colors.size == 1) return SolidColor(colors.first())
    return Brush.verticalGradient(colorStops = stops.toTypedArray())
}

fun Modifier.tvContentBackground(): Modifier = background(tvContentBrush())

private val DarkColors = darkColorScheme(
    primary = TipBlue,
    onPrimary = TipOnAmber,
    secondary = TipAccent,
    onSecondary = TipOnAmber,
    background = DarkTipBg,
    onBackground = TipGoldText,
    surface = DarkTipSurface,
    onSurface = TipGoldText,
    surfaceVariant = DarkTipSurfaceAlt,
    onSurfaceVariant = TipGoldMuted,
    outline = Color(0xFF2A3544)
)

private val LightColors = lightColorScheme(
    primary = TipBlue,
    onPrimary = TipOnAmber,
    secondary = Color(0xFFF57C00),
    onSecondary = Color.White,
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF101820),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101820),
    surfaceVariant = Color(0xFFE8EEF6),
    onSurfaceVariant = Color(0xFF5A6A7A),
    outline = Color(0xFFC5D0DC)
)

private fun tipTypography(onBg: Color) = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, color = onBg),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = onBg),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = onBg),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, color = onBg),
    bodyLarge = TextStyle(fontSize = 15.sp, color = onBg),
    bodyMedium = TextStyle(fontSize = 13.sp, color = onBg),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, color = onBg)
)

@Composable
fun TipTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val onBg: Color
    if (darkTheme) {
        TipBg = DarkTipBg
        TipSurface = DarkTipSurface
        TipSurfaceAlt = DarkTipSurfaceAlt
        TipOnBg = TipGoldText
        TipMuted = TipGoldMuted
        tipContentGloss = true
        onBg = TipGoldText
    } else {
        TipBg = Color(0xFFF4F7FB)
        TipSurface = Color(0xFFFFFFFF)
        TipSurfaceAlt = Color(0xFFE8EEF6)
        TipOnBg = Color(0xFF101820)
        TipMuted = Color(0xFF5A6A7A)
        tipContentGloss = false
        onBg = Color(0xFF101820)
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = tipTypography(onBg),
        content = {
            // Screens use raw backgrounds (not Surface); without this, Text defaults to black.
            CompositionLocalProvider(LocalContentColor provides onBg, content = content)
        }
    )
}

/** Compact ★ rating pill for poster overlays (only when score > 0). */
@Composable
fun RatingBadge(score: Double, modifier: Modifier = Modifier) {
    if (score <= 0.0) return
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(TipOnAmber.copy(alpha = 0.88f))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = "★ " + String.format("%.1f", score),
            color = TipBlue,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}
