package com.totaliptv.pro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.model.WatchProgress
import com.totaliptv.pro.ui.theme.BrandBlue
import com.totaliptv.pro.ui.theme.CinemaSurfaceHigh
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import com.totaliptv.pro.ui.theme.LiveMarker

@Composable
fun PhonePosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: WatchProgress? = null
) {
    val surface = CinemaSurfaceHigh
    val muted = OnCinemaMuted
    val on = OnCinema
    val brand = BrandBlue
    val watch = progress
    Column(
        modifier = modifier
            .width(120.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(surface)
        ) {
            val art = item.artworkUrl()
            if (!art.isNullOrBlank()) {
                AsyncImage(
                    model = art,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = item.name.take(1).uppercase(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = muted,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            val rating = item.displayRating()
            if (rating != null) {
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(bottomStart = 8.dp),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        text = rating,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            if (watch != null && watch.shouldResume()) {
                val frac = watch.fraction()
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp),
                    color = brand,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = on,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun PhoneLiveRow(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRecord: (() -> Unit)? = null,
    recordActive: Boolean = false,
    recordLabel: String = "Record"
) {
    val surface = CinemaSurfaceHigh
    val muted = OnCinemaMuted
    val on = OnCinema
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(surface),
            contentAlignment = Alignment.Center
        ) {
            val art = item.artworkUrl()
            if (!art.isNullOrBlank()) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                )
            } else {
                Text(text = item.name.take(1), color = muted)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = on,
                fontWeight = FontWeight.SemiBold
            )
            val group = item.groupTitle?.takeIf { it.isNotBlank() }
            if (group != null) {
                Text(text = group, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        if (onRecord != null) {
            if (recordActive) {
                androidx.compose.material3.Button(
                    onClick = onRecord,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = LiveMarker,
                        contentColor = OnCinema
                    )
                ) {
                    Text(recordLabel, fontWeight = FontWeight.Bold)
                }
            } else {
                androidx.compose.material3.TextButton(onClick = onRecord) {
                    Text(recordLabel)
                }
            }
        }
    }
}

@Composable
fun PhoneSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = OnCinema,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
