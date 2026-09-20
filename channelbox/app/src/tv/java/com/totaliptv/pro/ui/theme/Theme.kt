package com.totaliptv.pro.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

@OptIn(ExperimentalTvMaterial3Api::class)
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
            border = tip.border,
            inverseSurface = tip.onCinema,
            inverseOnSurface = tip.cinemaBg
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
            border = tip.border,
            inverseSurface = tip.onCinema,
            inverseOnSurface = tip.cinemaBg
        )
    }
    val density = LocalDensity.current
    val classicDensity = remember(density) {
        Density(
            density = density.density,
            fontScale = density.fontScale * ClassicDimens.Scale
        )
    }
    CompositionLocalProvider(
        LocalTipColors provides tip,
        LocalDensity provides classicDensity
    ) {
        MaterialTheme(
            colorScheme = scheme,
            content = content
        )
    }
}
