package com.totaliptv.pro.desktop.ui

import com.totaliptv.pro.desktop.data.SeriesPlayback
import com.totaliptv.pro.desktop.input.SeriesNextHotkeys
import com.totaliptv.pro.desktop.util.AppPaths
import java.awt.Font
import java.awt.FontMetrics
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max

/**
 * Pixel size of the next-episode banner. Text is measured (not given a fixed
 * window or button height) so a taller font, HiDPI scale, or GNOME
 * text-scaling-factor cannot clip the bottom of a label.
 */
internal object NextEpisodeBannerLayout {
    const val KICKER_SP = 11f
    const val TITLE_SP = 13f
    const val BODY_SP = 11f
    const val BUTTON_SP = 12f
    const val HINT_SP = 11f
    const val BASE_WIDTH_DP = 440f
    const val PAD_H_DP = 10f
    const val PAD_V_DP = 8f
    const val GAP_DP = 6f
    const val BUTTON_PAD_H_DP = 12f
    const val BUTTON_PAD_V_DP = 8f
    /** Material3 button minimum. The banner grows past this when the label is taller. */
    const val BUTTON_MIN_DP = 40f
    const val MARGIN_DP = 64f
    private const val SLACK_PX = 4

    const val LINUX_HINT =
        "Advances at end of episode · ${SeriesNextHotkeys.CTRL_RIGHT_HINT} when this app is focused — not VLC’s Next"
    const val WINDOWS_HINT =
        "${SeriesNextHotkeys.CTRL_RIGHT_HINT} or ${SeriesNextHotkeys.MEDIA_NEXT_HINT} — not VLC’s Next"

    fun hint(windows: Boolean): String = if (windows) WINDOWS_HINT else LINUX_HINT

    fun copyFor(session: ActiveSeriesPlay, recording: Boolean, windows: Boolean): BannerCopy {
        val next = session.next
        val last = next == null
        val primary = if (next != null) {
            "Next S${next.season}E${next.episodeNum}"
        } else {
            SeriesPlayback.LAST_EPISODE_MESSAGE
        }
        return BannerCopy(
            kicker = if (last) "LAST EPISODE" else "NEXT EPISODE",
            title = session.seriesName.ifBlank { "Series" },
            nowLine = "Now S${session.current.season}E${session.current.episodeNum}",
            primaryLabel = primary,
            recordLabel = if (recording) "Recording…" else "Record",
            hint = hint(windows)
        )
    }

    fun marginPx(density: Double): Int = px(MARGIN_DP, density).coerceAtLeast(24)

    /**
     * GNOME `text-scaling-factor` (a multiplier such as 1.25) or the Windows
     * accessibility text scale (a percent such as 125 or `0x7d`). Blank input is 1.
     */
    fun parseDesktopTextScale(raw: String?): Double {
        if (raw.isNullOrBlank()) return 1.0
        val hex = Regex("""0x([0-9a-fA-F]+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull(16)
        val number = hex?.toDouble()
            ?: Regex("""\d+(?:[.,]\d+)?""").find(raw)?.value?.replace(',', '.')?.toDoubleOrNull()
            ?: return 1.0
        val factor = if (number > 4.0) number / 100.0 else number
        return factor.coerceIn(0.5, 3.0)
    }

    fun currentDesktopTextScale(): Double {
        cachedTextScale?.let { return it }
        val scale = runCatching {
            if (AppPaths.isWindows) readWindowsTextScale() else readGnomeTextScale()
        }.getOrDefault(1.0)
        val clamped = scale.coerceIn(0.5, 3.0)
        cachedTextScale = clamped
        return clamped
    }

    fun measure(
        density: Double,
        textScale: Double,
        copy: BannerCopy,
        measurer: BannerTextMeasurer
    ): BannerSize {
        val d = density.coerceAtLeast(0.5)
        val t = textScale.coerceIn(0.5, 3.0)
        val padX = px(PAD_H_DP, d)
        val padY = px(PAD_V_DP, d)
        val gap = px(GAP_DP, d)
        val buttonPadX = px(BUTTON_PAD_H_DP, d)
        val buttonPadY = px(BUTTON_PAD_V_DP, d)
        val buttonFont = sp(BUTTON_SP, d, t)
        val labels = listOf(copy.primaryLabel, copy.recordLabel, "Hide", "Stop")
        val labelSizes = labels.map { measurer.measure(it, buttonFont, bold = true, maxWidthPx = 4_000, maxLines = 1) }
        val rowWidth = labelSizes.sumOf { it.widthPx + buttonPadX * 2 } + gap * (labels.size - 1)
        val inner = max(px(BASE_WIDTH_DP, d) - padX * 2, rowWidth)
        val width = inner + padX * 2
        val buttonText = labelSizes.maxOf { it.heightPx }
        val buttonPad = buttonPadY * 2
        val buttonHeight = max(px(BUTTON_MIN_DP, d), buttonText + buttonPad)
        val kicker = measurer.measure(copy.kicker, sp(KICKER_SP, d, t), bold = true, inner, maxLines = 1)
        val title = measurer.measure(copy.title, sp(TITLE_SP, d, t), bold = true, inner, maxLines = 2)
        val now = measurer.measure(copy.nowLine, sp(BODY_SP, d, t), bold = false, inner, maxLines = 1)
        val hint = measurer.measure(copy.hint, sp(HINT_SP, d, t), bold = false, inner, maxLines = 8)
        val textPx = kicker.heightPx + title.heightPx + now.heightPx + hint.heightPx
        val chromePx = padY * 2 + gap * 2 + buttonHeight + SLACK_PX
        return BannerSize(
            widthPx = width,
            heightPx = textPx + chromePx,
            buttonHeightPx = buttonHeight,
            buttonTextPx = buttonText,
            buttonPadPx = buttonPad,
            textPx = textPx,
            chromePx = chromePx
        )
    }

    private fun sp(size: Float, density: Double, textScale: Double): Float =
        (size * density * textScale).toFloat()

    private fun px(dp: Float, density: Double): Int =
        ceil(dp * density).toInt().coerceAtLeast(0)

    private fun readGnomeTextScale(): Double {
        val raw = runCommand(listOf("gsettings", "get", "org.gnome.desktop.interface", "text-scaling-factor"))
        return parseDesktopTextScale(raw)
    }

    private fun readWindowsTextScale(): Double {
        val raw = runCommand(
            listOf("reg", "query", """HKCU\Software\Microsoft\Accessibility""", "/v", "TextScaleFactor")
        )
        return parseDesktopTextScale(raw)
    }

    private fun runCommand(command: List<String>): String? {
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            if (!process.waitFor(700, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return null
            }
            process.inputStream.bufferedReader().readText()
        } catch (_: Throwable) {
            null
        }
    }

    @Volatile
    private var cachedTextScale: Double? = null
}

internal data class BannerCopy(
    val kicker: String,
    val title: String,
    val nowLine: String,
    val primaryLabel: String,
    val recordLabel: String,
    val hint: String
)

internal data class BannerSize(
    val widthPx: Int,
    val heightPx: Int,
    val buttonHeightPx: Int,
    val buttonTextPx: Int,
    val buttonPadPx: Int,
    val textPx: Int,
    val chromePx: Int
) {
    fun fitsText(): Boolean =
        buttonHeightPx >= buttonTextPx + buttonPadPx && heightPx >= textPx + chromePx
}

internal data class BannerTextSize(val widthPx: Int, val heightPx: Int)

internal fun interface BannerTextMeasurer {
    fun measure(text: String, fontPx: Float, bold: Boolean, maxWidthPx: Int, maxLines: Int): BannerTextSize
}

/** Logical-font measurement at an identity transform, in CSS pixels. */
internal object AwtBannerTextMeasurer : BannerTextMeasurer {
    override fun measure(
        text: String,
        fontPx: Float,
        bold: Boolean,
        maxWidthPx: Int,
        maxLines: Int
    ): BannerTextSize {
        val font = Font(
            Font.SANS_SERIF,
            if (bold) Font.BOLD else Font.PLAIN,
            ceil(fontPx.coerceAtLeast(1f).toDouble()).toInt().coerceAtLeast(1)
        )
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        return try {
            graphics.font = font
            wrap(graphics.fontMetrics, text, maxWidthPx.coerceAtLeast(1), maxLines.coerceAtLeast(1))
        } finally {
            graphics.dispose()
        }
    }

    private fun wrap(metrics: FontMetrics, text: String, maxWidth: Int, maxLines: Int): BannerTextSize {
        val lineHeight = metrics.height.coerceAtLeast(1)
        if (text.isEmpty()) return BannerTextSize(0, lineHeight)
        val words = text.split(' ')
        val lines = ArrayList<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isEmpty() || metrics.stringWidth(candidate) <= maxWidth) {
                current = candidate
            } else {
                lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current
        val shown = lines.take(maxLines)
        val width = shown.maxOf { metrics.stringWidth(it) }.coerceAtLeast(0)
        return BannerTextSize(width, lineHeight * shown.size)
    }
}
