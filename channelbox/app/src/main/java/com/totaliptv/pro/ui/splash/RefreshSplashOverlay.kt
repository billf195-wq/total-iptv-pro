package com.totaliptv.pro.ui.splash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.totaliptv.pro.data.repo.ManualRefresh

/**
 * Full-screen LogoBannerSplash shown on top of the app while a manual playlist
 * reload runs. Same timing as app start (SplashTiming 15s logo / 30s catalog cap).
 * The content underneath stays composed (so the reload coroutine is not cancelled
 * and focus/scroll state is untouched); keys are swallowed by the activity while
 * [ManualRefresh.overlayVisible] is true. When done, roots are asked to go Home.
 */
@Composable
fun RefreshSplashOverlay() {
    val epoch by ManualRefresh.epoch.collectAsState()
    val active by ManualRefresh.active.collectAsState()
    var handledEpoch by remember { mutableIntStateOf(ManualRefresh.epoch.value) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(epoch) {
        if (epoch != handledEpoch) visible = true
    }
    if (!visible) return

    DisposableEffect(Unit) {
        ManualRefresh.overlayVisible = true
        onDispose { ManualRefresh.overlayVisible = false }
    }
    BackHandler(enabled = true) { /* wait for the reload */ }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        val ready = active == 0
        LogoBannerSplash(
            ready = ready,
            statusMessage = if (ready) null else "Updating Live / Movies / Series...",
            onFinished = {
                handledEpoch = ManualRefresh.epoch.value
                visible = false
                ManualRefresh.overlayVisible = false
                ManualRefresh.requestHome()
            }
        )
    }
}
