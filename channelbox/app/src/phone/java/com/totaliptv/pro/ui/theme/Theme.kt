package com.totaliptv.pro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

@Composable
fun TotalIptvProTheme(
    appearance: AppearanceMode = AppearanceMode.DARK,
    accent: AccentPreset = AccentPreset.BLUE,
    content: @Composable () -> Unit
) {
    val tip = remember(appearance, accent) { tipColorsFor(appearance, accent) }
    val scheme = if (tip.isDark) {
        darkColorScheme(
            primary = tip.brand,
            onPrimary = Color.White,
            secondary = tip.brandSoft,
            onSecondary = Color.White,
            tertiary = tip.focusBorder,
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
            onPrimary = Color.White,
            secondary = tip.brandSoft,
            onSecondary = Color.White,
            tertiary = tip.focusBorder,
            background = tip.cinemaBg,
            onBackground = tip.onCinema,
            surface = tip.cinemaSurface,
            onSurface = tip.onCinema,
            surfaceVariant = tip.cinemaSurfaceHigh,
            onSurfaceVariant = tip.onCinemaMuted,
            outline = tip.border
        )
    }
    CompositionLocalProvider(LocalTipColors provides tip) {
        MaterialTheme(
            colorScheme = scheme,
            content = content
        )
    }
}
