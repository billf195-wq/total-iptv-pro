@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.totaliptv.pro.ui.home

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Stops the vertical Home list from sliding a fully visible row to the focus
 * pivot. Poster rows opt back into the normal spec so D-pad Right still scrolls.
 */
object NoVerticalHomeScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@Composable
fun PosterRowBringIntoView(
    spec: BringIntoViewSpec,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
