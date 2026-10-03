package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.totaliptv.pro.desktop.AppVersion

/**
 * Full-screen logo banner shown on launch.
 * Duration is shared with Windows, Linux (GTR), and Shield so every format matches.
 */
object SplashTiming {
    /** Logo banner stays up for 15 seconds on Linux and Windows. */
    const val DURATION_MS: Long = 15_000L
    /** Catalog load may continue after the logo; this cap is separate from splash. */
    const val MAX_CATALOG_HOLD_MS: Long = 30_000L
}

@Composable
fun SplashScreen(banner: ImageBitmap? = rememberSplashBanner()) {
    Box(
        Modifier
            .fillMaxSize()
            .tvContentBackground(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (banner != null) {
                Image(
                    bitmap = banner,
                    contentDescription = SplashBranding.APP_TITLE,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            SplashBrandTitle(
                versionName = AppVersion.VERSION_NAME,
                titleSize = 28.sp,
                versionSize = 22.sp,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun rememberSplashBanner(): ImageBitmap? = androidx.compose.runtime.remember {
    runCatching {
        useResource("app_banner.png") { loadImageBitmap(it) }
    }.getOrNull()
}
