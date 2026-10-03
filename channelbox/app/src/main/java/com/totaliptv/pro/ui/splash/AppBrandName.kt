package com.totaliptv.pro.ui.splash

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.totaliptv.pro.ui.home.HomeShelfFit

/** One-line app name. No logo picture and no version. */
@Composable
fun AppBrandName(
    color: Color,
    modifier: Modifier = Modifier,
    text: String = SplashBranding.APP_TITLE
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = HomeShelfFit.brandNameSp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center
    )
}
