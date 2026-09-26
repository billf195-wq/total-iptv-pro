package com.totaliptv.pro.ui.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import coil.compose.AsyncImage
import com.totaliptv.pro.BuildConfig
import com.totaliptv.pro.R
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.dvr.DvrRecordUi
import com.totaliptv.pro.ui.theme.LiveMarker
import com.totaliptv.pro.ui.splash.SplashBranding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TipFocusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    content: @Composable (focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(TipDimens.NavCorner))
            .border(
                width = if (focused) TipDimens.FocusBorder else TipDimens.dp(1),
                color = if (focused) TipAmber else Color.Transparent,
                shape = RoundedCornerShape(TipDimens.NavCorner)
            )
            .background(if (focused) TipSurfaceAlt else Color.Transparent)
            .focusable()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content(focused)
    }
}

@Composable
fun DesktopPosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    focusRequester: FocusRequester? = null
) {
    val safeName = item.name.ifBlank { "Untitled" }
    val cardMod = if (modifier === Modifier) {
        Modifier.width(TipDimens.PosterWidth)
    } else {
        modifier.widthIn(max = TipDimens.PosterWidth)
    }
    TipFocusable(onClick = onClick, modifier = cardMod, focusRequester = focusRequester) { focused ->
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
                val url = runCatching { item.artworkUrl() }.getOrNull()
                if (!url.isNullOrBlank()) {
                    val context = LocalContext.current
                    val model = remember(url) {
                        ImageRequest.Builder(context)
                            .data(url)
                            .size(200, 300)
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = model,
                        contentDescription = safeName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            safeName.take(1).ifEmpty { "?" },
                            color = TipAmber,
                            fontSize = TipDimens.PosterPlaceholderSp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                val ratingLabel = item.displayRating()
                if (ratingLabel != null) {
                    Text(
                        text = "★ $ratingLabel",
                        color = TipAccent,
                        fontSize = TipDimens.sp(11),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(TipDimens.dp(5))
                            .background(Color(0xCC000000), RoundedCornerShape(TipDimens.dp(8)))
                            .padding(horizontal = TipDimens.dp(6), vertical = TipDimens.dp(2))
                    )
                }
            }
            if (showTitle) {
                Spacer(Modifier.height(TipDimens.PosterPad))
                Text(
                    safeName,
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
    modifier: Modifier = Modifier,
    onRecord: (() -> Unit)? = null,
    recordActive: Boolean = false
) {
    TipFocusable(onClick = onClick, modifier = modifier.fillMaxWidth()) { focused ->
        Row(
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
            item.groupTitle?.takeIf { it.isNotBlank() }?.let { group ->
                Text(group, color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp)
            }
            if (onRecord != null) {
                TipFocusable(onClick = onRecord) { recFocused ->
                    Text(
                        if (recordActive) DvrRecordUi.ACTIVE_LABEL else "REC",
                        color = when {
                            recordActive -> Color.White
                            recFocused -> TipOnAmber
                            else -> TipAccent
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = TipDimens.LabelLargeSp,
                        modifier = Modifier
                            .padding(start = TipDimens.dp(8))
                            .background(
                                when {
                                    recordActive -> LiveMarker
                                    recFocused -> TipAmber
                                    else -> TipSurfaceAlt
                                },
                                RoundedCornerShape(TipDimens.NavCorner)
                            )
                            .padding(horizontal = TipDimens.dp(8), vertical = TipDimens.dp(4))
                    )
                }
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
    // Dedicated top-left logo slot. app_banner is a wide 1280x720 asset — Crop+Start
    // keeps the brand mark readable without spilling into the sidebar/content below.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TipDimens.BannerHeight)
            .background(TipBg)
            .padding(start = TipDimens.dp(20), end = TipDimens.dp(16), top = TipDimens.dp(12), bottom = TipDimens.dp(12)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.app_banner),
            contentDescription = SplashBranding.APP_TITLE,
            modifier = Modifier
                .height(TipDimens.dp(96))
                .width(TipDimens.dp(320))
                .clip(RoundedCornerShape(TipDimens.dp(10))),
            contentScale = ContentScale.Crop,
            alignment = Alignment.CenterStart
        )
        Spacer(Modifier.width(TipDimens.dp(14)))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = SplashBranding.APP_TITLE,
                color = TipGoldText,
                fontWeight = FontWeight.SemiBold,
                fontSize = TipDimens.sp(22),
                maxLines = 1
            )
            Spacer(Modifier.width(TipDimens.dp(10)))
            Text(
                text = SplashBranding.versionLabel(BuildConfig.VERSION_NAME),
                color = TipGoldMuted,
                fontWeight = FontWeight.Medium,
                fontSize = TipDimens.sp(14),
                maxLines = 1
            )
        }
        Spacer(Modifier.weight(1f))
    }
}
