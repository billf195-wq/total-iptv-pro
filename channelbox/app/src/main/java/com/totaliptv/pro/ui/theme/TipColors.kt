package com.totaliptv.pro.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/** Dark (default cinema) or Light. Shared by TV and phone. */
enum class AppearanceMode(val storageValue: String, val label: String) {
    DARK("dark", "Dark"),
    LIGHT("light", "Light");

    companion object {
        fun fromStorage(raw: String?): AppearanceMode =
            entries.firstOrNull { it.storageValue == raw } ?: DARK
    }
}

/** Accent applied to chips, primary buttons, live markers. */
enum class AccentPreset(
    val storageValue: String,
    val label: String,
    val primary: Color,
    val soft: Color,
    val focus: Color
) {
    BLUE("blue", "Blue", Color(0xFF5BA3F5), Color(0xFF3D7FD4), Color(0xFF7EB8F7)),
    PURPLE("purple", "Purple", Color(0xFFB388FF), Color(0xFF7E57C2), Color(0xFFD1C4E9)),
    TEAL("teal", "Teal", Color(0xFF26C6DA), Color(0xFF00838F), Color(0xFF80DEEA)),
    GREEN("green", "Green", Color(0xFF66BB6A), Color(0xFF2E7D32), Color(0xFFA5D6A7)),
    AMBER("amber", "Amber", Color(0xFFFFB74D), Color(0xFFF57C00), Color(0xFFFFE082)),
    RED("red", "Red", Color(0xFFEF5350), Color(0xFFC62828), Color(0xFFEF9A9A));

    companion object {
        fun fromStorage(raw: String?): AccentPreset =
            entries.firstOrNull { it.storageValue == raw } ?: BLUE
    }
}

data class TipColors(
    val brand: Color,
    val brandSoft: Color,
    val brandPill: Color,
    val focusBorder: Color,
    val liveMarker: Color,
    val accentCyan: Color,
    val cinemaBg: Color,
    val cinemaBgElevated: Color,
    val cinemaSurface: Color,
    val cinemaSurfaceHigh: Color,
    val onCinema: Color,
    val onCinemaMuted: Color,
    val warningAmber: Color,
    val border: Color,
    val isDark: Boolean
)

fun tipColorsFor(appearance: AppearanceMode, accent: AccentPreset): TipColors {
    val isDark = appearance == AppearanceMode.DARK
    return TipColors(
        brand = accent.primary,
        brandSoft = accent.soft,
        brandPill = accent.primary,
        focusBorder = accent.focus,
        liveMarker = accent.primary,
        accentCyan = accent.primary,
        cinemaBg = if (isDark) Color(0xFF000000) else Color(0xFFF4F6FA),
        cinemaBgElevated = if (isDark) Color(0xFF0E1219) else Color(0xFFFFFFFF),
        cinemaSurface = if (isDark) Color(0xFF141A24) else Color(0xFFFFFFFF),
        cinemaSurfaceHigh = if (isDark) Color(0xFF1B2433) else Color(0xFFDDE3EE),
        onCinema = if (isDark) Color(0xFFF1F5F9) else Color(0xFF0F172A),
        onCinemaMuted = if (isDark) Color(0xFF8B9BB0) else Color(0xFF4A5A6C),
        warningAmber = Color(0xFFFFB74D),
        border = if (isDark) Color(0xFF2A3548) else Color(0xFFCBD5E1),
        isDark = isDark
    )
}

val LocalTipColors = staticCompositionLocalOf {
    tipColorsFor(AppearanceMode.DARK, AccentPreset.BLUE)
}

val CinemaBg: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.cinemaBg

val CinemaBgElevated: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.cinemaBgElevated

val CinemaSurface: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.cinemaSurface

val CinemaSurfaceHigh: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.cinemaSurfaceHigh

val BrandBlue: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.brand

val BrandBlueSoft: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.brandSoft

val BrandBluePill: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.brandPill

val OnCinema: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.onCinema

val OnCinemaMuted: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.onCinemaMuted

val FocusBorder: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.focusBorder

val AccentCyan: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.accentCyan

val LiveMarker: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.liveMarker

val WarningAmber: Color
    @Composable @ReadOnlyComposable
    get() = LocalTipColors.current.warningAmber

val Hairline: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalTipColors.current.isDark) Color.White.copy(alpha = 0.14f)
    else Color.Black.copy(alpha = 0.16f)

val SoftOverlay: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalTipColors.current.isDark) Color.White.copy(alpha = 0.06f)
    else Color.Black.copy(alpha = 0.06f)

@Composable
@ReadOnlyComposable
fun tipScreenBrush(): Brush {
    val tip = LocalTipColors.current
    return if (tip.isDark) {
        SolidColor(Color(0xFF000000))
    } else {
        Brush.verticalGradient(
            listOf(tip.cinemaBg, tip.cinemaBgElevated, Color(0xFFEEF1F6))
        )
    }
}
