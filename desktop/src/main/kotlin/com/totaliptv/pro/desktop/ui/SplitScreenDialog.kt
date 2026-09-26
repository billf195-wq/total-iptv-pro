package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.totaliptv.pro.desktop.data.MediaItem

@Composable
fun SplitScreenDialog(
    liveChannels: List<MediaItem>,
    initialLeft: MediaItem?,
    initialRight: MediaItem? = null,
    onDismiss: () -> Unit,
    onLaunch: (MediaItem, MediaItem) -> Unit
) {
    var leftChannel by remember { mutableStateOf<MediaItem?>(initialLeft) }
    var rightChannel by remember { mutableStateOf<MediaItem?>(initialRight) }
    var selectingSide by remember { mutableStateOf(if (initialLeft == null) "LEFT" else "RIGHT") }
    var searchQuery by remember { mutableStateOf("") }

    val filteredChannels = remember(liveChannels, searchQuery) {
        if (searchQuery.isBlank()) liveChannels.take(60)
        else liveChannels.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.groupTitle?.contains(searchQuery, ignoreCase = true) == true
        }.take(60)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (tipContentDark) TipContentBlack else TipSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, TipBlue.copy(alpha = 0.5f)),
            modifier = Modifier.width(680.dp).height(620.dp)
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VerticalSplit, contentDescription = null, tint = TipBlue, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Dual Split Screen · Game Day Mode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TipOnBg)
                        Text("Watch 2 games side-by-side on your screen", style = MaterialTheme.typography.bodyMedium, color = TipMuted)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TipOnBg)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Left vs Right Selector Cards
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Left Game Card
                    val isLeftActive = selectingSide == "LEFT"
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isLeftActive) TipBlue.copy(alpha = 0.15f) else TipSurfaceAlt,
                        border = androidx.compose.foundation.BorderStroke(if (isLeftActive) 2.dp else 1.dp, if (isLeftActive) TipBlue else TipSurfaceAlt),
                        modifier = Modifier.weight(1f).clickable { selectingSide = "LEFT" }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("◄ LEFT GAME (Screen 1)", fontWeight = FontWeight.Bold, color = if (isLeftActive) TipBlue else TipMuted, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(6.dp))
                            if (leftChannel != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RemoteArtwork(
                                        url = leftChannel?.logoUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
                                        fallbackIcon = Icons.Default.LiveTv,
                                        contentScale = ContentScale.Fit
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(leftChannel!!.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = TipOnBg)
                                }
                            } else {
                                Text("Click to select Left game", color = TipMuted, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    // Right Game Card
                    val isRightActive = selectingSide == "RIGHT"
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isRightActive) TipBlue.copy(alpha = 0.15f) else TipSurfaceAlt,
                        border = androidx.compose.foundation.BorderStroke(if (isRightActive) 2.dp else 1.dp, if (isRightActive) TipBlue else TipSurfaceAlt),
                        modifier = Modifier.weight(1f).clickable { selectingSide = "RIGHT" }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("RIGHT GAME (Screen 2) ►", fontWeight = FontWeight.Bold, color = if (isRightActive) TipBlue else TipMuted, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(6.dp))
                            if (rightChannel != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RemoteArtwork(
                                        url = rightChannel?.logoUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
                                        fallbackIcon = Icons.Default.LiveTv,
                                        contentScale = ContentScale.Fit
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(rightChannel!!.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = TipOnBg)
                                }
                            } else {
                                Text("Click to select Right game", color = TipMuted, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Search box
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search channel for ${if (selectingSide == "LEFT") "Left" else "Right"} game…") },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = TipBlue) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().trackTextInputFocus(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TipBlue,
                        unfocusedBorderColor = TipSurfaceAlt,
                        focusedContainerColor = TipSurfaceAlt,
                        unfocusedContainerColor = TipSurfaceAlt,
                        focusedTextColor = TipOnBg,
                        unfocusedTextColor = TipOnBg
                    )
                )

                Spacer(Modifier.height(10.dp))

                // Channel selection list
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(TipSurfaceAlt).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredChannels, key = { it.id }) { ch ->
                        val isSelected = (selectingSide == "LEFT" && leftChannel?.id == ch.id) ||
                                         (selectingSide == "RIGHT" && rightChannel?.id == ch.id)
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) TipBlue.copy(alpha = 0.3f) else TipSurface)
                                .clickable {
                                    if (selectingSide == "LEFT") {
                                        leftChannel = ch
                                        if (rightChannel == null) selectingSide = "RIGHT"
                                    } else {
                                        rightChannel = ch
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RemoteArtwork(
                                url = ch.logoUrl,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)),
                                fallbackIcon = Icons.Default.LiveTv,
                                contentScale = ContentScale.Fit
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, color = TipOnBg)
                                ch.groupTitle?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = TipMuted, maxLines = 1)
                                }
                            }
                            Button(
                                onClick = {
                                    if (selectingSide == "LEFT") {
                                        leftChannel = ch
                                        if (rightChannel == null) selectingSide = "RIGHT"
                                    } else {
                                        rightChannel = ch
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) TipBlue else TipSurfaceAlt,
                                    contentColor = if (isSelected) TipOnAmber else TipOnBg
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(if (selectingSide == "LEFT") "Pick Left" else "Pick Right", fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Bottom action bar
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TipMuted)
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            val l = leftChannel
                            val r = rightChannel
                            if (l != null && r != null) {
                                onLaunch(l, r)
                            }
                        },
                        enabled = leftChannel != null && rightChannel != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TipBlue,
                            contentColor = TipOnAmber,
                            disabledContainerColor = TipSurfaceAlt,
                            disabledContentColor = TipMuted
                        ),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Icon(Icons.Default.VerticalSplit, contentDescription = null, tint = TipOnAmber)
                        Spacer(Modifier.width(8.dp))
                        Text("Watch Both Games", fontWeight = FontWeight.Bold, color = TipOnAmber)
                    }
                }
            }
        }
    }
}
