package com.totaliptv.pro.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.totaliptv.pro.BuildConfig
import com.totaliptv.pro.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single splash module for TV + phone.
 *
 * Lives in [com.totaliptv.pro.ui.splash] so leftover copies of
 * `com.totaliptv.pro.ui.StartupSplash` / `SplashTiming` / `StartupSplashGate`
 * (main or tv source set) cannot redeclare these types.
 *
 * 15s is the logo banner; 30s is a separate catalog-hold cap.
 */
object SplashTiming {
    const val DURATION_MS: Long = 15_000L
    const val MAX_CATALOG_HOLD_MS: Long = 30_000L
}

object SplashBranding {
    const val APP_TITLE = "Total IPTV Pro"
    fun versionLabel(versionName: String): String = versionName.trim()

    /** Short label overlaid on the banner, e.g. "v1.4.63". */
    fun bannerVersionLabel(versionName: String): String {
        val version = versionLabel(versionName)
        return if (version.isEmpty()) "" else "v$version"
    }
}

/** Amber version on a dark-amber pill, in a corner of the banner art. */
@Composable
fun BannerVersionBadge(
    versionName: String,
    modifier: Modifier = Modifier
) {
    val label = SplashBranding.bannerVersionLabel(versionName)
    if (label.isEmpty()) return
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF1A1200).copy(alpha = 0.88f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = Color(0xFFFFB300),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

object StartupSplashGate {
    @Volatile var shownThisProcess: Boolean = false
}

@Composable
fun LogoBannerSplash(
    ready: Boolean = true,
    statusMessage: String? = "Updating Live / Movies / Series...",
    onFinished: () -> Unit
) {
    var finished by remember { mutableStateOf(false) }
    var minLogoDone by remember { mutableStateOf(false) }
    fun finishOnce() {
        if (finished) return
        finished = true
        StartupSplashGate.shownThisProcess = true
        onFinished()
    }

    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.72f) }
    val statusAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            logoAlpha.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
        }
        logoScale.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        delay(180)
        delay(400)
        statusAlpha.animateTo(1f, tween(350, easing = FastOutSlowInEasing))
        val already = 700L + 180L + 400L + 350L
        val remain = (SplashTiming.DURATION_MS - already).coerceAtLeast(0L)
        delay(remain)
        minLogoDone = true
    }

    LaunchedEffect(ready, minLogoDone) {
        if (ready && minLogoDone) {
            delay(280)
            finishOnce()
        }
    }

    LaunchedEffect(Unit) {
        delay(SplashTiming.MAX_CATALOG_HOLD_MS)
        finishOnce()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .padding(horizontal = 24.dp)
                    .aspectRatio(1280f / 720f)
                    .scale(logoScale.value)
                    .alpha(logoAlpha.value)
            ) {
                Image(
                    painter = painterResource(R.drawable.app_banner),
                    contentDescription = SplashBranding.APP_TITLE,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                BannerVersionBadge(
                    versionName = BuildConfig.VERSION_NAME,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 6.dp)
                )
            }
            if (!statusMessage.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = statusMessage,
                    color = Color(0xFF90A4AE),
                    fontSize = 16.sp,
                    modifier = Modifier.alpha(statusAlpha.value)
                )
            }
        }
    }
}
