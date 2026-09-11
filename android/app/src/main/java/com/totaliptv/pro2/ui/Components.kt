package com.totaliptv.pro2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.totaliptv.pro2.data.MediaItem

@Composable
fun TipFocusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(TipDimens.NavCorner))
            .border(
                width = if (focused) TipDimens.FocusBorder else TipDimens.dp(1),
                color = if (focused) TipAmber else Color.Transparent,
                shape = RoundedCornerShape(TipDimens.NavCorner)
            )
            .background(if (focused) TipSurfaceAlt else Color.Transparent)
            .focusable()
            .androidx_clickable(onClick),
        contentAlignment = Alignment.Center
    ) {
        content(focused)
    }
}

private fun Modifier.androidx_clickable(onClick: () -> Unit): Modifier =
    clickable(onClick = onClick)

@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true
) {
    // Desktop baseline 140.dp / 13.sp × TipDimens.Scale; grid caps at PosterWidth so cells don't inflate
    val cardMod = if (modifier == Modifier) {
        modifier.width(TipDimens.PosterWidth)
    } else {
        modifier.widthIn(max = TipDimens.PosterWidth)
    }
    TipFocusable(onClick = onClick, modifier = cardMod) { focused ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(TipSurface)
                .padding(TipDimens.PosterPad)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(TipDimens.PosterCorner))
                    .background(TipSurfaceAlt)
                    .border(
                        if (focused) TipDimens.FocusBorder else TipDimens.dp(0),
                        if (focused) TipAccent else Color.Transparent,
                        RoundedCornerShape(TipDimens.PosterCorner)
                    )
            ) {
                val url = item.artworkUrl()
                if (url != null) {
                    AsyncImage(
                        model = url,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            item.name.take(1),
                            color = TipAmber,
                            fontSize = TipDimens.PosterPlaceholderSp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            if (showTitle) {
                Spacer(Modifier.height(TipDimens.PosterPad))
                Text(
                    item.name,
                    color = if (focused) TipAccent else TipGoldText,
                    fontSize = TipDimens.PosterTitleSp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun LiveRowItem(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TipFocusable(onClick = onClick, modifier = modifier.fillMaxWidth()) { focused ->
        androidx.compose.foundation.layout.Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TipDimens.NavCorner))
                .background(if (focused) TipSurfaceAlt else TipSurface)
                .padding(horizontal = TipDimens.LivePadH, vertical = TipDimens.LivePadV),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(TipDimens.LiveThumb)
                    .clip(RoundedCornerShape(TipDimens.PosterCorner))
                    .background(TipSurfaceAlt)
            ) {
                val url = item.logoUrl ?: item.artworkUrl()
                if (url != null) {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Text(
                item.name,
                color = if (focused) TipAccent else TipGoldText,
                fontSize = TipDimens.BodyLargeSp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = TipDimens.dp(12))
                    .weight(1f)
            )
            if (!item.groupTitle.isNullOrBlank()) {
                Text(item.groupTitle!!, color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp)
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = TipGoldText,
        fontSize = TipDimens.TitleLargeSp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(bottom = TipDimens.dp(10))
    )
}

@Composable
fun PaneTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = TipGoldText,
        fontSize = TipDimens.HeadlineMediumSp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(bottom = TipDimens.dp(4))
    )
}

@Composable
fun AmberButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TipFocusable(onClick = onClick, modifier = modifier) { focused ->
        Box(
            Modifier
                .background(if (focused) TipAccent else TipAmber, RoundedCornerShape(TipDimens.dp(6)))
                .padding(horizontal = TipDimens.ButtonPadH, vertical = TipDimens.ButtonPadV)
        ) {
            Text(label, color = TipOnAmber, fontWeight = FontWeight.Bold, fontSize = TipDimens.LabelLargeSp)
        }
    }
}

@Composable
fun TopBanner(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(com.totaliptv.pro2.R.drawable.app_banner),
        contentDescription = "Total IPTV Pro",
        modifier = modifier
            .fillMaxWidth()
            .height(TipDimens.BannerHeight)
            .background(TipSurface)
            .padding(horizontal = TipDimens.dp(12), vertical = TipDimens.dp(8)),
        contentScale = ContentScale.Fit,
        alignment = Alignment.Center
    )
}
