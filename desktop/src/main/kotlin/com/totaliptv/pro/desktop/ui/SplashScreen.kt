package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp

/**
 * Full-screen logo banner shown on launch.
 * Duration is shared with Windows, Linux (GTR), and Shield so every format matches.
 */
object SplashTiming {
    const val DURATION_MS: Long = 2500L
}

@Composable
fun SplashScreen(banner: ImageBitmap? = rememberSplashBanner()) {
    Box(
        Modifier
            .fillMaxSize()
            .background(TipBg),
        contentAlignment = Alignment.Center
    ) {
        if (banner != null) {
            Image(
                bitmap = banner,
                contentDescription = "Total IPTV Pro",
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center
            )
        }
    }
}

@Composable
private fun rememberSplashBanner(): ImageBitmap? = androidx.compose.runtime.remember {
    runCatching {
        useResource("app_banner.png") { loadImageBitmap(it) }
    }.getOrNull()
}
