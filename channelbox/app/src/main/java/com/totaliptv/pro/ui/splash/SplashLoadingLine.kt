package com.totaliptv.pro.ui.splash

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Text under the splash title: EPG for 5 s, VOD for 5 s, then Series. */
object SplashLoadingText {
    const val STEP_MS = 5_000L

    fun labelAt(elapsedMs: Long): String = when {
        elapsedMs < STEP_MS -> "Loading EPG"
        elapsedMs < 2 * STEP_MS -> "Loading VOD"
        else -> "Loading Series"
    }
}

/** Small spinning circle plus the cycling loading label. */
@Composable
fun SplashLoadingLine(modifier: Modifier = Modifier) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (elapsed < 2 * SplashLoadingText.STEP_MS) {
            delay(250)
            elapsed = System.currentTimeMillis() - start
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = Color(0xFFFFB300),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = SplashLoadingText.labelAt(elapsed),
            color = Color(0xFFB0BEC5),
            fontSize = 14.sp
        )
    }
}
