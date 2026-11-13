package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 1.2.27: pixels scrolled per wheel notch on a chip row. */
internal const val CHIP_ROW_WHEEL_STEP_PX = 80f

/** 1.2.27: wheel → horizontal. Uses whichever axis moved more (vertical wheel,
 *  Shift+wheel which arrives as x on desktop, or a touchpad sideways swipe). */
internal fun chipRowWheelDelta(dx: Float, dy: Float): Float =
    (if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) dx else dy) * CHIP_ROW_WHEEL_STEP_PX

/** 1.2.27: wide, always-visible Settings scroll bar. */
val TipWideScrollbarStyle = ScrollbarStyle(
    minimalHeight = 48.dp,
    thickness = 14.dp,
    shape = RoundedCornerShape(7.dp),
    hoverDurationMillis = 200,
    unhoverColor = Color(0xB34FC3F7),
    hoverColor = Color(0xFF4FC3F7)
)

/**
 * 1.2.27: horizontal chip/tab row that scrolls with the mouse wheel and Shift+wheel,
 * moves focus with Left/Right, keeps the selected chip in view, and shows thin
 * arrows at each end when there is more to see.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun ScrollableChipRow(
    selectedIndex: Int = -1,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: LazyListScope.() -> Unit
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val canBack by remember { derivedStateOf { state.canScrollBackward } }
    val canFwd by remember { derivedStateOf { state.canScrollForward } }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 0) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo
        val fully = visible.any { it.index == selectedIndex &&
            it.offset >= 0 && it.offset + it.size <= state.layoutInfo.viewportEndOffset }
        if (!fully) state.animateScrollToItem(selectedIndex)
    }

    fun page(forward: Boolean) {
        val w = state.layoutInfo.viewportSize.width.coerceAtLeast(200)
        scope.launch { state.animateScrollBy(if (forward) w * 0.8f else -w * 0.8f) }
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (canBack) {
            IconButton(onClick = { page(false) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Scroll left", tint = TipMuted)
            }
        }
        Box(Modifier.weight(1f)) {
            LazyRow(
                state = state,
                horizontalArrangement = Arrangement.spacedBy(spacing),
                modifier = Modifier
                    .fillMaxWidth()
                    .onPointerEvent(PointerEventType.Scroll) { ev ->
                        val d = ev.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                        val px = chipRowWheelDelta(d.x, d.y)
                        if (px != 0f) {
                            scope.launch { state.scrollBy(px) }
                            ev.changes.forEach { it.consume() }
                        }
                    }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
                            Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
                            else -> false
                        }
                    },
                content = content
            )
        }
        if (canFwd) {
            IconButton(onClick = { page(true) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Scroll right", tint = TipMuted)
            }
        }
    }
}
