package com.totaliptv.pro2.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/**
 * Full-screen logo banner shown on launch.
 * Duration is shared with the Windows / Linux desktop app so Shield matches Bigboybill and GTR.
 */
object SplashTiming {
    const val DURATION_MS: Long = 2500L
}

@Composable
fun SplashScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(TipBg),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(com.totaliptv.pro2.R.drawable.app_banner),
            contentDescription = "Total IPTV Pro",
            modifier = Modifier
                .fillMaxSize()
                .padding(TipDimens.dp(24)),
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center
        )
    }
}
