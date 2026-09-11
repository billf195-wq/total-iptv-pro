package com.totaliptv.pro2.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

val TipAmber = Color(0xFFFFB300)
val TipAmberDark = Color(0xFFFF8F00)
val TipAccent = Color(0xFFFFD54F)
val TipGoldText = Color(0xFFFFE082)
val TipGoldMuted = Color(0xFFC9A84C)
val TipOnAmber = Color(0xFF1A1200)
val TipBg = Color(0xFF0B0F14)
val TipSurface = Color(0xFF141A22)
val TipSurfaceAlt = Color(0xFF1C2430)
val TipOutline = Color(0xFF2A3544)

private val DarkColors = darkColorScheme(
    primary = TipAmber,
    onPrimary = TipOnAmber,
    secondary = TipAccent,
    onSecondary = TipOnAmber,
    background = TipBg,
    onBackground = TipGoldText,
    surface = TipSurface,
    onSurface = TipGoldText,
    surfaceVariant = TipSurfaceAlt,
    onSurfaceVariant = TipGoldMuted,
    outline = TipOutline
)

/** Typography scaled via TipDimens (TV 0.70× desktop). */
private val TipTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = TipDimens.HeadlineLargeSp, color = TipGoldText),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = TipDimens.HeadlineMediumSp, color = TipGoldText),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = TipDimens.TitleLargeSp, color = TipGoldText),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = TipDimens.TitleMediumSp, color = TipGoldText),
    bodyLarge = TextStyle(fontSize = TipDimens.BodyLargeSp, color = TipGoldText),
    bodyMedium = TextStyle(fontSize = TipDimens.BodyMediumSp, color = TipGoldText),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = TipDimens.LabelLargeSp, color = TipGoldText)
)

@Composable
fun TipTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = TipTypography
    ) {
        CompositionLocalProvider(LocalContentColor provides TipGoldText, content = content)
    }
}
