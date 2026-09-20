package com.totaliptv.pro.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.totaliptv.pro.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cold-start logo banner: stays up for [SplashTiming.DURATION_MS], then until
 * [ready] or [SplashTiming.MAX_CATALOG_HOLD_MS]. Named distinctly so it cannot
 * clash with an old `StartupSplash` left in the TV source set.
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

    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.72f) }
    val titleAlpha = remember { Animatable(0f) }
    val statusAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            logoAlpha.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
        }
        logoScale.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        delay(180)
        titleAlpha.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        delay(400)
        statusAlpha.animateTo(1f, tween(350, easing = FastOutSlowInEasing))
        val already = 700L + 180L + 420L + 400L + 350L
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
            .background(Color(0xFF0B0F14)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.app_banner),
                contentDescription = null,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .padding(horizontal = 24.dp)
                    .scale(logoScale.value)
                    .alpha(logoAlpha.value),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Total IPTV Pro",
                color = Color(0xFFFFE082),
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.alpha(titleAlpha.value)
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
