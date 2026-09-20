package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared splash / logo-banner title + high-contrast version chip. */
object SplashBranding {
    const val APP_TITLE = "Total IPTV Pro"

    fun versionLabel(versionName: String): String = versionName.trim()

    /** Taskbar / window title so Linux (GTR) can confirm the running build without reading the splash. */
    fun windowTitle(versionName: String): String {
        val version = versionLabel(versionName)
        return if (version.isEmpty()) APP_TITLE else "$APP_TITLE $version"
    }
}

@Composable
fun SplashBrandTitle(
    versionName: String,
    modifier: Modifier = Modifier,
    titleSize: TextUnit = 28.sp,
    versionSize: TextUnit = 20.sp,
    titleColor: Color = TipGoldText
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = SplashBranding.APP_TITLE,
            color = titleColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = titleSize,
            maxLines = 1
        )
        Spacer(Modifier.width(12.dp))
        SplashVersionChip(
            versionName = versionName,
            fontSize = versionSize
        )
    }
}

/** Dark pill so the version stays readable on gold banner art (TipGoldMuted disappeared). */
@Composable
fun SplashVersionChip(
    versionName: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(TipOnAmber)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = SplashBranding.versionLabel(versionName),
            color = TipBlue,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            maxLines = 1
        )
    }
}
