package com.totaliptv.pro.ui.home

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
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
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.totaliptv.pro.ui.components.NetworkImage
import com.totaliptv.pro.ui.focus.SafeFocus
import com.totaliptv.pro.ui.theme.BrandBlue
import com.totaliptv.pro.ui.theme.CinemaSurface
import com.totaliptv.pro.ui.theme.FocusBorder
import com.totaliptv.pro.ui.theme.OnCinemaMuted

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
)

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
 * and D-pad-focusable Play / Info actions. A single daily pick is shown;
 * the banner does not rotate or decode a second still.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeHeroBanner(
    title: String,
    imageUrl: String?,
    metaLine: String?,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
    playFocus: FocusRequester? = null,
    infoFocus: FocusRequester? = null,
    onDownFromBanner: () -> Boolean = { false },
    onLeftFromPlay: () -> Boolean = { false },
    colors: HomeHeroColors = classicHomeHeroColors()
) {
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
        NetworkImage(
            url = imageUrl,
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
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(18.dp)
                .fillMaxWidth(0.78f)
        ) {
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
                    onClick = onPlay,
                    focusRequester = playFocus,
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
                    onClick = onInfo,
                    focusRequester = infoFocus,
                    onDown = onDownFromBanner,
                    onLeft = { playFocus != null && SafeFocus.request(playFocus) }
                )
            }
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
    onRight: () -> Boolean = { false }
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Button(
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
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
