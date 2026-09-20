package com.totaliptv.pro2.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.totaliptv.pro2.BuildConfig

/**
 * Full-screen logo banner shown on launch.
 * Duration is shared with the Windows / Linux desktop app so Shield matches Bigboybill and GTR.
 */
object SplashTiming {
    /** Logo banner stays up for 15 seconds so Shield matches desktop. */
    const val DURATION_MS: Long = 15_000L
    /** Catalog load may continue after the logo; this cap is separate from splash. */
    const val MAX_CATALOG_HOLD_MS: Long = 30_000L
}

object SplashBranding {
    const val APP_TITLE = "Total IPTV Pro"
    fun versionLabel(versionName: String): String = versionName.trim()
}

@Composable
fun SplashScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(TipBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(com.totaliptv.pro2.R.drawable.app_banner),
                contentDescription = SplashBranding.APP_TITLE,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = TipDimens.dp(24), top = TipDimens.dp(24), end = TipDimens.dp(24), bottom = TipDimens.dp(8)),
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = TipDimens.dp(24))
            ) {
                Text(
                    text = SplashBranding.APP_TITLE,
                    color = TipGoldText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                    maxLines = 1
                )
                Spacer(Modifier.width(TipDimens.dp(10)))
                Text(
                    text = SplashBranding.versionLabel(BuildConfig.VERSION_NAME),
                    color = TipGoldMuted,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(TipDimens.dp(28)))
        }
    }
}
