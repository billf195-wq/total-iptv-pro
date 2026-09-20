package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared splash / logo-banner title + smaller version suffix. */
object SplashBranding {
    const val APP_TITLE = "Total IPTV Pro"

    fun versionLabel(versionName: String): String = versionName.trim()
}

@Composable
fun SplashBrandTitle(
    versionName: String,
    modifier: Modifier = Modifier,
    titleSize: TextUnit = 28.sp,
    versionSize: TextUnit = 16.sp,
    titleColor: Color = TipGoldText,
    versionColor: Color = TipGoldMuted
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = SplashBranding.APP_TITLE,
            color = titleColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = titleSize,
            maxLines = 1
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = SplashBranding.versionLabel(versionName),
            color = versionColor,
            fontWeight = FontWeight.Medium,
            fontSize = versionSize,
            maxLines = 1
        )
    }
}
