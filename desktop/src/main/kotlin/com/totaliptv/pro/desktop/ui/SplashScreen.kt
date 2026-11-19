package com.totaliptv.pro.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.Text

/**
 * Full-screen splash: animated TV graphics + spinning load line.
 * 1.2.26 personal: same TV banner as the Store edition, shown after sign-in
 * (cold start when the saved login exists, or right after the first sign-in).
 * No version number on the banner (personal pref); the window title keeps it.
 */
object SplashTiming {
    /** TV banner stays up for 15 seconds after sign-in (Linux and Windows). */
    const val DURATION_MS: Long = 15_000L
    const val MAX_CATALOG_HOLD_MS: Long = 30_000L
}

private val TvScreenPerspectiveShape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(0f, 0f)
    lineTo(w, h * (76f / 368f))
    lineTo(w, h * (349f / 368f))
    lineTo(0f, h)
    close()
}

@Composable
fun SplashScreen() {
    val frame = rememberSplashBitmap("app_banner_frame.png")
    val screen = rememberSplashBitmap("app_banner_screen.png")
    val clouds = rememberSplashBitmap("app_banner_clouds.png")
    // Fallback if layered assets missing
    val flat = rememberSplashBitmap("app_banner.png")

    val titleLeftOffset = remember { Animatable(-160f) }
    val titleRightOffset = remember { Animatable(160f) }
    val titleAlpha = remember { Animatable(0f) }
    val titleScale = remember { Animatable(0.92f) }
    val bannerAlpha = remember { Animatable(0f) }
    val bannerScale = remember { Animatable(0.85f) }

    val infiniteTransition = rememberInfiniteTransition(label = "splashTv")
    val tvScreenSlope = 76f / 438f
    val landscapePanX by infiniteTransition.animateFloat(
        initialValue = -35f, targetValue = 35f,
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse),
        label = "landscapePanX"
    )
    val landscapePanY by infiniteTransition.animateFloat(
        initialValue = -5f, targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing), RepeatMode.Reverse),
        label = "landscapePanY"
    )
    val landscapeScale by infiniteTransition.animateFloat(
        initialValue = 1.22f, targetValue = 1.34f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse),
        label = "landscapeScale"
    )
    val cloudPanX by infiniteTransition.animateFloat(
        initialValue = -45f, targetValue = 45f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse),
        label = "cloudPanX"
    )
    val cloudAlpha by infiniteTransition.animateFloat(
        initialValue = 0.65f, targetValue = 0.90f,
        animationSpec = infiniteRepeatable(tween(5500, easing = LinearEasing), RepeatMode.Reverse),
        label = "cloudAlpha"
    )
    val sheenProgress by infiniteTransition.animateFloat(
        initialValue = -0.6f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            tween(3200, delayMillis = 3500, easing = FastOutSlowInEasing),
            RepeatMode.Restart
        ),
        label = "sheenProgress"
    )
    val ambientGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(3600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ambientGlowAlpha"
    )

    LaunchedEffect(Unit) {
        launch { titleAlpha.animateTo(1f, tween(550, easing = FastOutSlowInEasing)) }
        launch { titleLeftOffset.animateTo(0f, tween(750, easing = FastOutSlowInEasing)) }
        launch { titleRightOffset.animateTo(0f, tween(750, easing = FastOutSlowInEasing)) }
        titleScale.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
        delay(80)
        titleScale.animateTo(1.05f, tween(140))
        titleScale.animateTo(1.0f, tween(140))
        delay(180)
        launch { bannerAlpha.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        bannerScale.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .tvContentBackground(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.weight(0.08f))
            if (bannerAlpha.value > 0.01f) {
                BoxWithConstraints(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .weight(1f, fill = false)
                        .graphicsLayer {
                            scaleX = bannerScale.value
                            scaleY = bannerScale.value
                            alpha = bannerAlpha.value
                        }
                ) {
                    val totalWidth = maxWidth
                    val totalHeight = totalWidth * (720f / 1280f)
                    val screenLeft = totalWidth * (110f / 1280f)
                    val screenTop = totalHeight * (72f / 720f)
                    val screenWidth = totalWidth * (438f / 1280f)
                    val screenHeight = totalHeight * (368f / 720f)

                    Box(Modifier.width(totalWidth).height(totalHeight)) {
                        if (frame != null && screen != null) {
                            Box(
                                modifier = Modifier
                                    .offset(x = screenLeft * 0.4f, y = totalHeight * 0.58f)
                                    .width(screenWidth * 1.55f)
                                    .height(totalHeight * 0.28f)
                                    .graphicsLayer { alpha = ambientGlowAlpha }
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                Color(0xFF00B0FF).copy(alpha = 0.45f),
                                                Color(0xFF0288D1).copy(alpha = 0.20f),
                                                Color.Transparent
                                            )
                                        )
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .offset(x = screenLeft, y = screenTop)
                                    .width(screenWidth)
                                    .height(screenHeight)
                                    .clip(TvScreenPerspectiveShape)
                                    .clipToBounds()
                            ) {
                                Image(
                                    bitmap = screen,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = landscapeScale
                                            scaleY = landscapeScale
                                            translationX = landscapePanX
                                            translationY = (landscapePanX * tvScreenSlope) + landscapePanY
                                        }
                                )
                                if (clouds != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(screenHeight * (200f / 368f))
                                            .align(Alignment.TopCenter)
                                    ) {
                                        Image(
                                            bitmap = clouds,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .graphicsLayer {
                                                    scaleX = 1.30f
                                                    scaleY = 1.30f
                                                    translationX = cloudPanX
                                                    translationY = cloudPanX * tvScreenSlope
                                                    alpha = cloudAlpha
                                                }
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .drawWithContent {
                                            drawContent()
                                            val w = size.width
                                            val h = size.height
                                            val cx = w * sheenProgress
                                            val sw = w * 0.35f
                                            drawRect(
                                                brush = Brush.linearGradient(
                                                    colors = listOf(
                                                        Color.Transparent,
                                                        Color.White.copy(alpha = 0.16f),
                                                        Color.Transparent
                                                    ),
                                                    start = Offset(cx - sw, 0f),
                                                    end = Offset(cx + sw, h)
                                                )
                                            )
                                        }
                                )
                            }
                            Image(
                                bitmap = frame,
                                contentDescription = SplashBranding.APP_TITLE,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (flat != null) {
                            Image(
                                bitmap = flat,
                                contentDescription = SplashBranding.APP_TITLE,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.graphicsLayer {
                    scaleX = titleScale.value
                    scaleY = titleScale.value
                    alpha = titleAlpha.value
                }
            ) {
                Text(
                    text = "TOTAL",
                    color = Color(0xFFFFE082),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.graphicsLayer { translationX = titleLeftOffset.value }
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "IPTV PRO",
                    color = Color(0xFFFFB300),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.graphicsLayer { translationX = titleRightOffset.value }
                )
            }
            Spacer(Modifier.height(14.dp))
            SplashLoadingLine(modifier = Modifier.alpha(titleAlpha.value))
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun rememberSplashBitmap(name: String): ImageBitmap? = remember(name) {
    runCatching { useResource(name) { loadImageBitmap(it) } }.getOrNull()
}
