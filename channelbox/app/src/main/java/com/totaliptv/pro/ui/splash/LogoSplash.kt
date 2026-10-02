package com.totaliptv.pro.ui.splash

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
}

/**
 * Home top bar is half the old 128.dp slot: a 64.dp row with 4.dp vertical
 * padding, so the artwork is 56.dp tall (half of 112.dp) and still 16:9.
 */
val DesktopBannerRowHeight = 64.dp
val DesktopBannerImageHeight = 56.dp

/**
 * The banner image only. The app name is painted in the asset. The version
 * stays in Settings, not on this image. No gold title is drawn over it.
 *
 * Pass a height (or [Modifier.fillMaxHeight] inside the 64.dp row) and leave
 * [matchHeightConstraintsFirst] true so width follows 16:9. Splash passes a
 * max width and sets [matchHeightConstraintsFirst] false.
 */
@Composable
fun AppBannerArt(
    modifier: Modifier = Modifier,
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
    }
}

object StartupSplashGate {
    @Volatile var shownThisProcess: Boolean = false
}

/**
 * Custom 4-corner perspective trapezoid clipping shape matching the angled TV screen:
 * TL: (0, 0)
 * TR: (width, height * (76 / 368))
 * BR: (width, height * (349 / 368))
 * BL: (0, height)
 */
private val TvScreenPerspectiveShape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(0f, 0f)
    lineTo(w, h * (76f / 368f))
    lineTo(w, h * (349f / 368f))
    lineTo(0f, h)
    close()
}

/**
 * Cold-start logo banner: animated title converge, banner artwork reveal,
 * then catalog update status until [ready].
 * Features GPU-accelerated perspective-matched motion, drifting clouds, OLED glass glare,
 * and ambient living room LED underglow.
 */
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

    val titleLeftOffset = remember { Animatable(-160f) }
    val titleRightOffset = remember { Animatable(160f) }
    val titleAlpha = remember { Animatable(0f) }
    val titleScale = remember { Animatable(0.92f) }

    val bannerAlpha = remember { Animatable(0f) }
    val bannerScale = remember { Animatable(0.85f) }

    val statusAlpha = remember { Animatable(0f) }

    val infiniteTransition = rememberInfiniteTransition(label = "bannerMotion")

    // The TV screen slants down to the right: slope dy/dx = 76 / 438 = 0.1735f
    val tvScreenSlope = 76f / 438f

    // Ultra-smooth GPU cinematic pan across landscape (wide drift, slow 18s duration)
    val landscapePanX by infiniteTransition.animateFloat(
        initialValue = -35f,
        targetValue = 35f,
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "landscapePanX"
    )
    val landscapePanY by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "landscapePanY"
    )
    val landscapeScale by infiniteTransition.animateFloat(
        initialValue = 1.22f,
        targetValue = 1.34f,
        animationSpec = infiniteRepeatable(
            animation = tween(16000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "landscapeScale"
    )

    // Ultra-smooth GPU drifting clouds across the sky (moving along TV slant angle)
    val cloudPanX by infiniteTransition.animateFloat(
        initialValue = -45f,
        targetValue = 45f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cloudPanX"
    )
    val cloudAlpha by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(5500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cloudAlpha"
    )

    // OLED Glass Sheen Sweep (periodic light reflection across TV screen glass)
    val sheenProgress by infiniteTransition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, delayMillis = 3500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sheenProgress"
    )

    // Living room ambient blue LED backlight breathing
    val ambientGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambientGlowAlpha"
    )

    LaunchedEffect(Unit) {
        // Step 1: Title halves converge from left & right to center
        launch {
            titleAlpha.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
        }
        launch {
            titleLeftOffset.animateTo(0f, tween(750, easing = FastOutSlowInEasing))
        }
        launch {
            titleRightOffset.animateTo(0f, tween(750, easing = FastOutSlowInEasing))
        }
        titleScale.animateTo(1f, tween(750, easing = FastOutSlowInEasing))

        // Gentle pulse on impact
        delay(80)
        titleScale.animateTo(1.05f, tween(140))
        titleScale.animateTo(1.0f, tween(140))

        delay(180)

        // Step 2: Banner artwork smoothly expands & fades in above the title
        launch {
            bannerAlpha.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        }
        bannerScale.animateTo(1f, tween(650, easing = FastOutSlowInEasing))

        delay(150)

        // Step 3: Catalog status fades in
        statusAlpha.animateTo(1f, tween(350, easing = FastOutSlowInEasing))

        val already = 750L + 80L + 280L + 180L + 650L + 150L + 350L
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            if (bannerAlpha.value > 0.01f) {
                BoxWithConstraints(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .padding(horizontal = 24.dp)
                        .graphicsLayer {
                            scaleX = bannerScale.value
                            scaleY = bannerScale.value
                            alpha = bannerAlpha.value
                        }
                ) {
                    val totalWidth = maxWidth
                    val totalHeight = totalWidth * (720f / 1280f)

                    // Coordinate ratios based on 1280x720 banner:
                    // TV screen display bounds: left=110, top=72, width=438, height=368
                    val screenLeft = totalWidth * (110f / 1280f)
                    val screenTop = totalHeight * (72f / 720f)
                    val screenWidth = totalWidth * (438f / 1280f)
                    val screenHeight = totalHeight * (368f / 720f)

                    Box(
                        modifier = Modifier
                            .width(totalWidth)
                            .height(totalHeight)
                    ) {
                        // 0. Dynamic ambient LED backlight glow under the console & behind TV
                        Box(
                            modifier = Modifier
                                .offset(x = screenLeft * 0.4f, y = totalHeight * 0.58f)
                                .width(screenWidth * 1.55f)
                                .height(totalHeight * 0.28f)
                                .graphicsLayer {
                                    alpha = ambientGlowAlpha
                                }
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

                        // 1. Moving TV screen contents clipped to the EXACT angled TV perspective polygon
                        Box(
                            modifier = Modifier
                                .offset(x = screenLeft, y = screenTop)
                                .width(screenWidth)
                                .height(screenHeight)
                                .clip(TvScreenPerspectiveShape)
                                .clipToBounds()
                        ) {
                            // Moving landscape (mountain & lake) along the TV's 3D slant angle
                            Image(
                                painter = painterResource(R.drawable.app_banner_screen),
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

                            // Moving clouds across the sky along the TV's 3D slant angle
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(screenHeight * (200f / 368f))
                                    .align(Alignment.TopCenter)
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.app_banner_clouds),
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

                            // Glass glare sheen sweep across the TV display along the slant angle
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

                        // 2. Crisp TV frame, bezel, room, underglow & stationary logos over the moving screen
                        Image(
                            painter = painterResource(R.drawable.app_banner_frame),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            // Title halves converge with GPU sub-pixel acceleration
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
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.graphicsLayer {
                        translationX = titleLeftOffset.value
                    }
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "IPTV PRO",
                    color = Color(0xFFFFB300),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.graphicsLayer {
                        translationX = titleRightOffset.value
                    }
                )
            }

            if (!statusMessage.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
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
