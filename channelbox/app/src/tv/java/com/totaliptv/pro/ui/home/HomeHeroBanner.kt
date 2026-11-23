package com.totaliptv.pro.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.totaliptv.pro.data.HeroBannerPick
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.ui.components.NetworkImage
import com.totaliptv.pro.ui.focus.SafeFocus
import com.totaliptv.pro.ui.theme.BrandBlue
import com.totaliptv.pro.ui.theme.CinemaSurface
import com.totaliptv.pro.ui.theme.FocusBorder
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import kotlinx.coroutines.delay

/** Colors for [HomeHeroBanner]. Classic is the default; Desktop passes amber/sidebar colors. */
data class HomeHeroColors(
    val surface: Color,
    val border: Color,
    val playContainer: Color,
    val playFocused: Color,
    val playContent: Color,
    val infoContainer: Color,
    val infoFocused: Color,
    val infoContent: Color,
    val title: Color,
    val meta: Color,
    val focusBorder: Color,
    val scrimStart: Color,
    val scrimMid: Color,
    val scrimEnd: Color
) {
}

@Composable
@ReadOnlyComposable
fun classicHomeHeroColors(): HomeHeroColors = HomeHeroColors(
    surface = CinemaSurface,
    border = Color(0x22FFFFFF),
    playContainer = BrandBlue,
    playFocused = BrandBlue,
    playContent = Color.White,
    infoContainer = Color(0xCC1A2230),
    infoFocused = Color(0xEE243044),
    infoContent = Color.White,
    title = Color.White,
    meta = OnCinemaMuted,
    focusBorder = FocusBorder,
    scrimStart = Color(0xE607090D),
    scrimMid = Color(0x9907090D),
    scrimEnd = Color(0x2207090D)
)

/**
 * Home hero: one downscaled backdrop (or poster), title, year, rating,
 * and D-pad-focusable Play / Info actions. When [items] has more than one
 * title, the banner rotates through them on a 10s timer with a soft
 * crossfade, preloading only the next still. Rotation pauses while Play or
 * Info has focus, and the timer is cancelled when this leaves composition
 * (Home not visible) or the app is in the background. Index changes never
 * request D-pad focus.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeHeroBanner(
    items: List<MediaItem>,
    onPlay: (MediaItem) -> Unit,
    onInfo: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    playFocus: FocusRequester? = null,
    infoFocus: FocusRequester? = null,
    onDownFromBanner: () -> Boolean = { false },
    onLeftFromPlay: () -> Boolean = { false },
    colors: HomeHeroColors = classicHomeHeroColors()
) {
    if (items.isEmpty()) return
    var currentIndex by remember(items.map { it.id }) { mutableIntStateOf(0) }
    val fade = remember { Animatable(0f) }
    var playFocused by remember { mutableStateOf(false) }
    var infoFocused by remember { mutableStateOf(false) }
    var inForeground by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { source, _ ->
            inForeground = source.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        inForeground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val safeIndex = currentIndex.coerceIn(0, items.lastIndex)
    val current = items[safeIndex]
    val next = if (items.size > 1) items[(safeIndex + 1) % items.size] else null
    val canRotate = items.size > 1 && !playFocused && !infoFocused && inForeground
    // Timer is a LaunchedEffect, so it stops when this leaves composition (Home
    // not visible) and when canRotate becomes false (Play/Info focused or the
    // app is in the background). Advancing the index never calls requestFocus.
    LaunchedEffect(items, canRotate) {
        if (!canRotate) {
            runCatching { fade.snapTo(0f) }
            return@LaunchedEffect
        }
        while (true) {
            delay(HeroBannerPick.ROTATION_INTERVAL_MS)
            var advanced = false
            try {
                fade.snapTo(0f)
                fade.animateTo(
                    1f,
                    tween(HeroBannerPick.ROTATION_CROSSFADE_MS, easing = FastOutSlowInEasing)
                )
                currentIndex = (currentIndex + 1) % items.size
                advanced = true
            } finally {
                if (!advanced && fade.value >= 0.5f && items.size > 1) {
                    currentIndex = (currentIndex + 1) % items.size
                }
                runCatching { fade.snapTo(0f) }
            }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val density = LocalDensity.current
    val height = HomeShelfFit.classicHeroHeight
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(colors.surface, shape)
            .border(1.dp, colors.border, shape)
    ) {
        // Cap at one 960x640 still (about 1.2 MB in RGB_565), never a full-resolution backdrop.
        val decodeWidth = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1).coerceAtMost(960)
        val decodeHeight = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1).coerceAtMost(640)
        HeroCrossfadeStills(
            current = current,
            next = next,
            fade = fade,
            colors = colors,
            decodeWidth = decodeWidth,
            decodeHeight = decodeHeight,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(18.dp)
                .fillMaxWidth(0.78f)
        ) {
            HeroCrossfadeCopy(
                current = current,
                next = next,
                fade = fade,
                colors = colors
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                HeroActionButton(
                    label = "Play",
                    container = colors.playContainer,
                    focusedContainer = colors.playFocused,
                    content = colors.playContent,
                    focusBorder = colors.focusBorder,
                    onClick = { onPlay(items[currentIndex.coerceIn(0, items.lastIndex)]) },
                    focusRequester = playFocus,
                    onFocusChanged = { playFocused = it },
                    onDown = onDownFromBanner,
                    onLeft = onLeftFromPlay,
                    onRight = { infoFocus != null && SafeFocus.request(infoFocus) }
                )
                HeroActionButton(
                    label = "Info",
                    container = colors.infoContainer,
                    focusedContainer = colors.infoFocused,
                    content = colors.infoContent,
                    focusBorder = colors.focusBorder,
                    onClick = { onInfo(items[currentIndex.coerceIn(0, items.lastIndex)]) },
                    focusRequester = infoFocus,
                    onFocusChanged = { infoFocused = it },
                    onDown = onDownFromBanner,
                    onLeft = { playFocus != null && SafeFocus.request(playFocus) }
                )
            }
        }
    }
}

@Composable
private fun HeroCrossfadeStills(
    current: MediaItem,
    next: MediaItem?,
    fade: Animatable<Float, *>,
    colors: HomeHeroColors,
    decodeWidth: Int,
    decodeHeight: Int,
    modifier: Modifier = Modifier
) {
    // Isolated from Play/Info so frame-by-frame fade updates cannot recompose
    // the D-pad targets or the rest of Home.
    val progress by fade.asState()
    Box(modifier) {
        HeroStill(
            item = current,
            title = current.name,
            metaLine = null,
            colors = colors,
            decodeWidth = decodeWidth,
            decodeHeight = decodeHeight,
            showCopy = false,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - progress }
        )
        if (next != null) {
            HeroStill(
                item = next,
                title = next.name,
                metaLine = null,
                colors = colors,
                decodeWidth = decodeWidth,
                decodeHeight = decodeHeight,
                showCopy = false,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress }
            )
        }
    }
}

@Composable
private fun HeroCrossfadeCopy(
    current: MediaItem,
    next: MediaItem?,
    fade: Animatable<Float, *>,
    colors: HomeHeroColors
) {
    val progress by fade.asState()
    Box(Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
        HeroCopy(
            title = current.name,
            metaLine = HeroBannerPick.metaLine(current),
            colors = colors,
            alpha = 1f - progress
        )
        if (next != null) {
            HeroCopy(
                title = next.name,
                metaLine = HeroBannerPick.metaLine(next),
                colors = colors,
                alpha = progress
            )
        }
    }
}

@Composable
private fun HeroStill(
    item: MediaItem,
    title: String,
    metaLine: String?,
    colors: HomeHeroColors,
    decodeWidth: Int,
    decodeHeight: Int,
    modifier: Modifier = Modifier,
    showCopy: Boolean = false
) {
    Box(modifier) {
        NetworkImage(
            url = HeroBannerPick.imageUrl(item) ?: item.backdropUrl ?: item.posterUrl,
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            placeholderLabel = title.take(1).uppercase(),
            decodeWidth = decodeWidth,
            decodeHeight = decodeHeight,
            rgb565 = true
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(colors.scrimStart, colors.scrimMid, colors.scrimEnd)
                    )
                )
        )
        if (showCopy) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(18.dp)
                    .fillMaxWidth(0.78f)
            ) {
                HeroCopy(title = title, metaLine = metaLine, colors = colors, alpha = 1f)
            }
        }
    }
}

@Composable
private fun HeroCopy(
    title: String,
    metaLine: String?,
    colors: HomeHeroColors,
    alpha: Float
) {
    Column(Modifier.graphicsLayer { this.alpha = alpha }) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colors.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!metaLine.isNullOrBlank()) {
            Text(
                text = metaLine,
                fontSize = 14.sp,
                color = colors.meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroActionButton(
    label: String,
    container: Color,
    focusedContainer: Color,
    content: Color,
    focusBorder: Color,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    onDown: () -> Boolean,
    onLeft: () -> Boolean = { false },
    onRight: () -> Boolean = { false },
    onFocusChanged: (Boolean) -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Button(
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .then(if (focused) Modifier.border(2.dp, focusBorder, shape) else Modifier)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> onDown()
                    Key.DirectionLeft -> onLeft()
                    Key.DirectionRight -> onRight()
                    else -> false
                }
            },
        colors = ButtonDefaults.colors(
            containerColor = container,
            focusedContainerColor = focusedContainer,
            contentColor = content,
            focusedContentColor = content
        )
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}
