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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
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

    /** app_banner.png is 1280×720. The top bar shows that whole frame, not a center crop. */
    const val BANNER_ASPECT_RATIO = 1280f / 720f

    fun versionLabel(versionName: String): String = versionName.trim()

    /** Short label overlaid on the banner, e.g. "v1.4.64". */
    fun bannerVersionLabel(versionName: String): String {
        val version = versionLabel(versionName)
        return if (version.isEmpty()) "" else "v$version"
    }
}

/**
 * Desktop b8990a6 top bar: a 128.dp row with 8.dp vertical padding, so the
 * artwork itself is 112.dp tall and 112 × 16:9 wide (about 199.dp).
 */
val DesktopBannerRowHeight = 128.dp
val DesktopBannerImageHeight = 112.dp

/** Amber version on a dark-amber pill. 12.sp, tight padding, not affected by TV font scale. */
@Composable
fun BannerVersionBadge(
    versionName: String,
    modifier: Modifier = Modifier
) {
    val label = SplashBranding.bannerVersionLabel(versionName)
    if (label.isEmpty()) return
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
        Box(
            modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFF1A1200).copy(alpha = 0.88f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = label,
                color = Color(0xFFFFB300),
                style = TextStyle(
                    color = Color(0xFFFFB300),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1
            )
        }
    }
}

/**
 * The full banner image. The title is painted in the asset. The version pill is
 * clipped to the image's lower-right, clear of that title.
 *
 * Pass a height (or [Modifier.fillMaxHeight] inside the 128.dp row) and leave
 * [matchHeightConstraintsFirst] true so width follows 16:9. Splash passes a
 * max width and sets [matchHeightConstraintsFirst] false.
 */
@Composable
fun AppBannerArt(
    modifier: Modifier = Modifier,
    versionName: String = BuildConfig.VERSION_NAME,
    contentDescription: String = SplashBranding.APP_TITLE,
    matchHeightConstraintsFirst: Boolean = true
) {
    Box(
        modifier
            .aspectRatio(SplashBranding.BANNER_ASPECT_RATIO, matchHeightConstraintsFirst)
            .clip(RoundedCornerShape(8.dp))
    ) {
        Image(
            painter = painterResource(R.drawable.app_banner),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart
        )
        BannerVersionBadge(
            versionName = versionName,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 6.dp)
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
            AppBannerArt(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .padding(horizontal = 24.dp)
                    .scale(logoScale.value)
                    .alpha(logoAlpha.value),
                matchHeightConstraintsFirst = false
            )
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
