package com.totaliptv.pro.ui.components

import androidx.compose.foundation.background
import com.totaliptv.pro.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.totaliptv.pro.artwork.ArtworkRole
import com.totaliptv.pro.artwork.ArtworkRuntime
import com.totaliptv.pro.artwork.tvImageRequest
import com.totaliptv.pro.ui.splash.AppBannerArt
import com.totaliptv.pro.ui.splash.DesktopBannerImageHeight
import com.totaliptv.pro.ui.splash.SplashBranding
import com.totaliptv.pro.ui.theme.BrandBlue
import com.totaliptv.pro.ui.theme.ClassicDimens
import com.totaliptv.pro.ui.theme.BrandBluePill
import com.totaliptv.pro.ui.theme.CinemaSurface
import com.totaliptv.pro.ui.theme.CinemaSurfaceHigh
import com.totaliptv.pro.ui.theme.FocusBorder
import com.totaliptv.pro.ui.theme.Hairline
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.SoftOverlay
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import com.totaliptv.pro.ui.theme.LiveMarker

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(horizontal = ClassicDimens.SectionTitlePadH, vertical = ClassicDimens.SectionTitlePadV)
    )
}

/** Top centered Live / Movies / Series pill */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TopNavPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val highlight = selected || focused
    val latestPillClick = rememberUpdatedState(onClick)
    Surface(
        onClick = onClick,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .pointerInput(label, selected) {
                detectTapGestures(onTap = { latestPillClick.value() })
            }
            .then(
                when {
                    focused && !selected -> Modifier.border(2.dp, FocusBorder, shape)
                    focused && selected -> Modifier.border(2.dp, Color.White.copy(alpha = 0.55f), shape)
                    else -> Modifier
                }
            ),
        shape = ClickableSurfaceDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) BrandBluePill else Color.Transparent,
            focusedContainerColor = if (selected) BrandBluePill else CinemaSurfaceHigh,
            pressedContainerColor = BrandBlue.copy(alpha = 0.35f)
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = when {
                selected -> Color.White // on solid BrandBluePill
                focused -> OnCinema
                else -> OnCinemaMuted
            },
            modifier = Modifier.padding(horizontal = ClassicDimens.NavPillPadH, vertical = ClassicDimens.NavPillPadV)
        )
    }
}

/** Compact top-bar action chip */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TopBarChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    active: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val latestClick = rememberUpdatedState(onClick)
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .pointerInput(label) {
                // TV Surface sometimes misses raw touch taps when not focused — ensure chips work with tap.
                detectTapGestures(onTap = { latestClick.value() })
            }
            .then(if (focused) Modifier.border(2.dp, FocusBorder, shape) else Modifier),
        shape = ClickableSurfaceDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                active -> LiveMarker
                emphasized -> BrandBlue.copy(alpha = 0.18f)
                else -> Color.Transparent
            },
            focusedContainerColor = if (active) LiveMarker else CinemaSurfaceHigh,
            pressedContainerColor = if (active) LiveMarker.copy(alpha = 0.85f) else BrandBlue.copy(alpha = 0.28f)
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active || focused || emphasized) OnCinema else OnCinemaMuted,
            modifier = Modifier.padding(horizontal = ClassicDimens.ChipPadH, vertical = ClassicDimens.ChipPadV)
        )
    }
}


/** Compact selectable sort chip (TV / D-pad focusable). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SortChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .then(
                when {
                    focused -> Modifier.border(2.dp, FocusBorder, shape)
                    selected -> Modifier.border(1.dp, BrandBlue.copy(alpha = 0.7f), shape)
                    else -> Modifier
                }
            ),
        shape = ClickableSurfaceDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) BrandBlue.copy(alpha = 0.28f) else Color.Transparent,
            focusedContainerColor = if (selected) BrandBlue.copy(alpha = 0.4f) else CinemaSurfaceHigh,
            pressedContainerColor = BrandBlue.copy(alpha = 0.35f)
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = when {
                // Translucent accent wash — use OnCinema so Light mode stays readable
                selected || focused -> OnCinema
                else -> OnCinemaMuted
            },
            modifier = Modifier.padding(horizontal = ClassicDimens.SortChipPadH, vertical = ClassicDimens.SortChipPadV)
        )
    }
}

/** Large dashboard tile - kept for secondary screens / onboarding fallbacks */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DashboardTile(
    title: String,
    subtitle: String,
    iconGlyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val base = modifier
        .widthIn(min = 200.dp, max = 280.dp)
        .height(168.dp)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged { focused = it.isFocused }

    Surface(
        onClick = onClick,
        modifier = base.then(
            if (focused) Modifier.border(2.5.dp, FocusBorder, shape) else Modifier
        ),
        shape = ClickableSurfaceDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CinemaSurface,
            focusedContainerColor = CinemaSurfaceHigh,
            pressedContainerColor = CinemaSurfaceHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (focused) BrandBlue.copy(alpha = 0.22f)
                        else SoftOverlay
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = iconGlyph,
                    fontSize = 22.sp,
                    color = if (focused) FocusBorder else OnCinemaMuted
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnCinemaMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FocusableCard(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val mod = (if (focusRequester != null) {
        modifier.widthIn(min = 200.dp, max = 320.dp).focusRequester(focusRequester)
    } else {
        modifier.widthIn(min = 200.dp, max = 320.dp)
    }).onFocusChanged { focused = it.isFocused }

    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = mod.then(
            if (focused) Modifier.border(2.dp, FocusBorder, shape) else Modifier
        ),
        shape = ClickableSurfaceDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CinemaSurface,
            focusedContainerColor = CinemaSurfaceHigh,
            pressedContainerColor = BrandBlue.copy(alpha = 0.18f)
        )
    ) {
        Column(
            modifier = Modifier.padding(PaddingValues(16.dp)),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = OnCinemaMuted
                )
            }
        }
    }
}

/** Slim category rail row with optional channel/title count */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CategoryRailItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val highlight = focused || selected
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(
                when {
                    focused -> Modifier.border(2.dp, FocusBorder, shape)
                    selected -> Modifier.border(1.dp, BrandBlue.copy(alpha = 0.45f), shape)
                    else -> Modifier
                }
            ),
        shape = ClickableSurfaceDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                selected && !focused -> BrandBlue.copy(alpha = 0.18f)
                selected && focused -> BrandBlue.copy(alpha = 0.28f)
                else -> Color.Transparent
            },
            focusedContainerColor = CinemaSurfaceHigh,
            pressedContainerColor = BrandBlue.copy(alpha = 0.22f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (highlight) MaterialTheme.colorScheme.onSurface else OnCinemaMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (count != null) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) BrandBluePill else OnCinemaMuted.copy(alpha = 0.85f),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

/** Featured now / coming-up panel for Live hub (TV-friendly substitute for embedded player) */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FeaturedNowPanel(
    channelName: String,
    logoUrl: String?,
    nowTitle: String?,
    nowTimeRange: String?,
    progress: Float?,
    synopsis: String?,
    comingUp: String?,
    onPlay: () -> Unit,
    onOpenGuide: (() -> Unit)?,
    onRecord: (() -> Unit)? = null,
    recordActive: Boolean = false,
    recordLabel: String = "Record",
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onPlay,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.5.dp, FocusBorder, shape) else Modifier),
        shape = ClickableSurfaceDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CinemaSurface,
            focusedContainerColor = CinemaSurfaceHigh,
            pressedContainerColor = CinemaSurfaceHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0C1018)),
                contentAlignment = Alignment.Center
            ) {
                NetworkImage(
                    url = logoUrl,
                    contentDescription = channelName,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                    contentScale = ContentScale.Fit,
                    placeholderLabel = channelName.take(2).uppercase(),
                    role = ArtworkRole.LOGO
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = channelName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = OnCinema,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = "  NOW",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandBluePill,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = nowTitle ?: "Select a channel to watch",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnCinema,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!nowTimeRange.isNullOrBlank() || progress != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp)
                    ) {
                        if (!nowTimeRange.isNullOrBlank()) {
                            Text(
                                text = nowTimeRange,
                                style = MaterialTheme.typography.labelSmall,
                                color = OnCinemaMuted,
                                maxLines = 1
                            )
                        }
                        if (progress != null) {
                            Box(
                                modifier = Modifier
                                    .padding(start = if (nowTimeRange.isNullOrBlank()) 0.dp else 8.dp)
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Hairline)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                                        .height(3.dp)
                                        .background(BrandBluePill)
                                )
                            }
                        }
                    }
                }
                if (!comingUp.isNullOrBlank()) {
                    Text(
                        text = "UP NEXT  |  $comingUp",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnCinemaMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (onRecord != null) {
                Spacer(Modifier.width(8.dp))
                TopBarChip(
                    label = recordLabel,
                    onClick = onRecord,
                    emphasized = true,
                    active = recordActive
                )
            }
            if (onOpenGuide != null) {
                Spacer(Modifier.width(8.dp))
                TopBarChip(label = "Guide", onClick = onOpenGuide, emphasized = true)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    isFavorite: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    rating: String? = null,
    /** 1–99 watch progress percent; shows badge + thin bar (Continue watching / in-progress). */
    progressPercent: Int? = null,
    focusRequester: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .width(ClassicDimens.PosterWidth)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) Modifier.border(2.5.dp, FocusBorder, shape) else Modifier
            ),
        shape = ClickableSurfaceDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CinemaSurface,
            focusedContainerColor = CinemaSurface,
            pressedContainerColor = CinemaSurface
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(Color(0xFF0C1018)),
                contentAlignment = Alignment.Center
            ) {
                NetworkImage(
                    url = imageUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    placeholderLabel = title.take(1).uppercase()
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .height(40.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xB3000000))
                            )
                        )
                )

                val ratingLabel = rating?.trim()?.takeIf {
                    it.isNotBlank() && it != "0" && it != "0.0" && !it.equals("N/A", true)
                }
                if (ratingLabel != null) {
                    Text(
                        text = "★ $ratingLabel",
                        color = Color(0xFFFFD54F),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(5.dp)
                            .background(
                                Color(0xCC000000),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                val prog = progressPercent?.coerceIn(1, 99)
                if (prog != null) {
                    Text(
                        text = "$prog%",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(5.dp)
                            .background(BrandBlue.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0x66000000))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(prog / 100f)
                                .background(BrandBlue)
                        )
                    }
                }

                // Heart badge: filled when favorited; empty hint when focused (TV long-press affordance)
                if (isFavorite || (focused && onLongClick != null)) {
                    Text(
                        text = if (isFavorite) "♥" else "♡",
                        color = if (isFavorite) Color(0xFFFF4D6D) else Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .background(
                                Color(0x99000000),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = OnCinemaMuted,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 5.dp)
                )
            }
        }
    }
}

/** Live channel tile - square logo grid, leanback-friendly */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelGridCard(
    title: String,
    logoUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(ClassicDimens.ChannelCardWidth)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) Modifier.border(2.5.dp, FocusBorder, shape) else Modifier
            ),
        shape = ClickableSurfaceDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CinemaSurface,
            focusedContainerColor = CinemaSurfaceHigh,
            pressedContainerColor = CinemaSurfaceHigh
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0C1018)),
                contentAlignment = Alignment.Center
            ) {
                NetworkImage(
                    url = logoUrl,
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    contentScale = ContentScale.Fit,
                    placeholderLabel = title.take(2).uppercase(),
                    role = ArtworkRole.LOGO
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 2.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelListItem(
    title: String,
    logoUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    var focused by remember { mutableStateOf(false) }
    val latestClick by rememberUpdatedState(onClick)
    var lastClickMs by remember { mutableStateOf(0L) }
    fun fireClick() {
        val now = System.currentTimeMillis()
        if (now - lastClickMs < 450L) return
        lastClickMs = now
        latestClick()
    }
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = { fireClick() },
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(title) {
                detectTapGestures(onTap = { fireClick() })
            }
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) Modifier.border(2.dp, FocusBorder, shape) else Modifier
            ),
        shape = ClickableSurfaceDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CinemaSurface,
            focusedContainerColor = CinemaSurfaceHigh,
            pressedContainerColor = BrandBlue.copy(alpha = 0.18f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0C1018)),
                contentAlignment = Alignment.Center
            ) {
                NetworkImage(
                    url = logoUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit,
                    placeholderLabel = title.take(1).uppercase(),
                    role = ArtworkRole.LOGO
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = OnCinemaMuted
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NetworkImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderLabel: String = "?",
    role: ArtworkRole = ArtworkRole.POSTER
) {
    if (url.isNullOrBlank()) {
        Box(modifier = modifier.background(Color(0xFF151C28)), contentAlignment = Alignment.Center) {
            Text(
                text = placeholderLabel,
                color = OnCinemaMuted,
                style = MaterialTheme.typography.titleMedium
            )
        }
        return
    }
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) {
        Box(modifier = modifier.background(Color(0xFF151C28)), contentAlignment = Alignment.Center) {
            Text(
                text = placeholderLabel,
                color = OnCinemaMuted,
                style = MaterialTheme.typography.titleMedium
            )
        }
    } else {
        val context = LocalContext.current
        val sharp by ArtworkRuntime.sharp.collectAsState()
        // Sharp mode decodes nearer the tile (w500) or detail (w780). Logos stay small.
        val model = remember(url, role, sharp) {
            tvImageRequest(context, url, role, sharp)
        }
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) failed = true
            }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ErrorText(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
    )
}


/** Global top nav: brand + Search/Home/Live/Movies/Series + settings (TIP chrome). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppTopNav(
    brandTitle: String,
    clockText: String,
    selectedTab: String,
    onSelectTab: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefreshData: (() -> Unit)? = null,
    userBadge: String? = null,
    sourceKind: String? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.padding(end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Full 16:9 frame (112.dp × ~199.dp). Image only; version stays in Settings.
            AppBannerArt(
                modifier = Modifier.height(DesktopBannerImageHeight),
                contentDescription = brandTitle.ifBlank { SplashBranding.APP_TITLE }
            )
            Text(
                text = clockText,
                style = MaterialTheme.typography.labelSmall,
                color = OnCinemaMuted,
                maxLines = 1
            )
        }
        Spacer(Modifier.weight(0.35f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopNavPill(
                label = "Search",
                selected = selectedTab == "Search",
                onClick = onOpenSearch
            )
            listOf("Home", "Live", "Movies", "Series").forEachIndexed { index, label ->
                TopNavPill(
                    label = label,
                    selected = selectedTab == label,
                    onClick = { onSelectTab(label) },
                    focusRequester = if (index == 0 && selectedTab == "Home") focusRequester else null
                )
            }
        }
        Spacer(Modifier.weight(0.6f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!userBadge.isNullOrBlank()) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = userBadge,
                        style = MaterialTheme.typography.labelLarge,
                        color = OnCinema,
                        maxLines = 1
                    )
                    if (!sourceKind.isNullOrBlank()) {
                        Text(
                            text = sourceKind,
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandBlue
                        )
                    }
                }
            }
            if (onRefreshData != null) {
                TopBarChip(
                    label = "Refresh data",
                    onClick = onRefreshData,
                    emphasized = true
                )
            }
            TopBarChip(label = "Settings", onClick = onOpenSettings)
        }
    }
}

/**
 * Home hero — large featured title over cinema gradient.
 * Generic streaming pattern; Total IPTV Pro blue accents only.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HeroFeatureBanner(
    title: String,
    imageUrl: String?,
    metaLine: String?,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    rating: String? = null,
    onPreview: (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onPlay,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.5.dp, FocusBorder, shape) else Modifier),
        shape = ClickableSurfaceDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CinemaSurface,
            focusedContainerColor = CinemaSurfaceHigh,
            pressedContainerColor = CinemaSurfaceHigh
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            NetworkImage(
                url = imageUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholderLabel = title.take(1).uppercase(),
                role = ArtworkRole.BACKDROP
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xE607090D), Color(0x9907090D), Color(0x3307090D))
                        )
                    )
            )
            val heroRating = rating?.trim()?.takeIf {
                it.isNotBlank() && it != "0" && it != "0.0" && !it.equals("N/A", true)
            }
            if (heroRating != null) {
                Text(
                    text = "★ $heroRating",
                    color = Color(0xFFFFD54F),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(18.dp)
                    .fillMaxWidth(0.72f)
            ) {
                Text(
                    text = "FEATURED",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandBluePill,
                    fontWeight = FontWeight.Bold
                )
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
                        style = MaterialTheme.typography.bodySmall,
                        color = OnCinemaMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (onPreview != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        TopBarChip(label = "▶ Play", onClick = onPlay, emphasized = true)
                        TopBarChip(label = "Preview", onClick = onPreview, emphasized = false)
                    }
                } else {
                    Text(
                        text = "OK to play",
                        style = MaterialTheme.typography.labelMedium,
                        color = BrandBlue,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

/** Horizontal poster rail label + row (Home / Movies featured strip). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SectionRowLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = modifier.padding(start = 2.dp, bottom = 6.dp, top = 10.dp)
    )
}

/**
 * Dense live channel cell — Player Zero–leaning list density with optional now/EPG line
 * and thin blue progress. TIP chrome (not a third-party card clone).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveChannelCard(
    title: String,
    logoUrl: String?,
    nowTitle: String?,
    progress: Float?,
    onClick: () -> Unit,
    onFocused: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    // Avoid stale click/focus lambdas when LazyVerticalGrid recycles TV Surfaces.
    val latestClick by rememberUpdatedState(onClick)
    val latestFocused by rememberUpdatedState(onFocused)
    // Debounce dual delivery: TV Surface onClick (DPAD) + pointer hit-test (mouse/emulator).
    var lastClickMs by remember { mutableStateOf(0L) }
    fun fireClick() {
        val now = System.currentTimeMillis()
        if (now - lastClickMs < 450L) return
        lastClickMs = now
        latestClick()
    }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = { fireClick() },
        modifier = modifier
            .fillMaxWidth()
            // Mouse/emulator taps hit-test this card; DPAD still uses Surface onClick.
            // Without this, Android TV often activates the focused neighbor instead of the tapped card.
            .pointerInput(title) {
                detectTapGestures(onTap = { fireClick() })
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) latestFocused?.invoke()
            }
            .then(if (focused) Modifier.border(2.dp, FocusBorder, shape) else Modifier),
        shape = ClickableSurfaceDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CinemaSurface,
            focusedContainerColor = CinemaSurfaceHigh,
            pressedContainerColor = BrandBlue.copy(alpha = 0.18f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0C1018)),
                    contentAlignment = Alignment.Center
                ) {
                    NetworkImage(
                        url = logoUrl,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        contentScale = ContentScale.Fit,
                        placeholderLabel = title.take(2).uppercase(),
                        role = ArtworkRole.LOGO
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = OnCinema
                    )
                    Text(
                        text = nowTitle?.takeIf { it.isNotBlank() } ?: "Live channel",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = OnCinemaMuted
                    )
                }
            }
            if (progress != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Hairline)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(2.dp)
                            .background(BrandBluePill)
                    )
                }
            }
        }
    }
}

