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

/**
 * Scrolls only far enough to make the focused child fully visible, including
 * [extraPadding] px of inset so a focus ring is not clipped at the edge.
 * Unlike the default pivot spec, this does not leave the first or last item
 * hanging halfway off the row, so D-pad Left can still land on it.
 */
object FullyVisibleBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        fullyVisibleScrollDistance(offset, size, containerSize)
}

fun fullyVisibleScrollDistance(
    offset: Float,
    size: Float,
    containerSize: Float,
    extraPadding: Float = 0f
): Float {
    val leading = offset - extraPadding
    val trailing = offset + size + extraPadding
    return when {
        leading >= 0f && trailing <= containerSize -> 0f
        size + extraPadding * 2f >= containerSize -> leading
        leading < 0f -> leading
        else -> trailing - containerSize
    }
}

@Composable
fun ChipRowBringIntoView(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides FullyVisibleBringIntoViewSpec, content = content)
}
