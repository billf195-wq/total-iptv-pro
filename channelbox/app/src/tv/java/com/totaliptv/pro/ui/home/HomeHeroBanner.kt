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

/**
 * Classic Home hero: one downscaled backdrop (or poster), title, year, rating,
 * and D-pad-focusable Play / Info actions.
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
    onDownFromBanner: () -> Boolean = { false }
) {
    val shape = RoundedCornerShape(12.dp)
    val density = LocalDensity.current
    val height = HomeShelfFit.classicHeroHeight
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(CinemaSurface, shape)
            .border(1.dp, Color(0x22FFFFFF), shape)
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
                        listOf(Color(0xE607090D), Color(0x9907090D), Color(0x2207090D))
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
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!metaLine.isNullOrBlank()) {
                Text(
                    text = metaLine,
                    fontSize = 14.sp,
                    color = OnCinemaMuted,
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
                    emphasized = true,
                    onClick = onPlay,
                    focusRequester = playFocus,
                    onDown = onDownFromBanner
                )
                HeroActionButton(
                    label = "Info",
                    emphasized = false,
                    onClick = onInfo,
                    focusRequester = infoFocus,
                    onDown = onDownFromBanner
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroActionButton(
    label: String,
    emphasized: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    onDown: () -> Boolean
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Button(
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.dp, FocusBorder, shape) else Modifier)
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && onDown()
            },
        colors = ButtonDefaults.colors(
            containerColor = if (emphasized) BrandBlue else Color(0xCC1A2230),
            focusedContainerColor = if (emphasized) BrandBlue else Color(0xEE243044),
            contentColor = Color.White,
            focusedContentColor = Color.White
        )
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}
