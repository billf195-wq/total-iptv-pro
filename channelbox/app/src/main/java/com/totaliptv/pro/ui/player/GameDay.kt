package com.totaliptv.pro.ui.player

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.ui.theme.BrandBlue
import com.totaliptv.pro.ui.theme.CinemaBg
import com.totaliptv.pro.ui.theme.CinemaSurface
import com.totaliptv.pro.ui.theme.CinemaSurfaceHigh
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted

object GameDayLauncher {
    fun start(context: Context, left: MediaItem, right: MediaItem) {
        val app = context.applicationContext as? TotalIptvProApp
        val playableLeft = app?.repository?.playableFrom(left) ?: left
        val playableRight = app?.repository?.playableFrom(right) ?: right
        if (playableLeft.streamUrl.isBlank() || playableRight.streamUrl.isBlank()) {
            Toast.makeText(context, "Both channels need a stream URL", Toast.LENGTH_SHORT).show()
            return
        }
        context.startActivity(SplitPlayerActivity.intent(context, playableLeft, playableRight))
    }
}

/**
 * Same picker as desktop Game Day: choose a left channel and a right channel,
 * then watch both. Rows are focusable for a TV remote.
 */
@Composable
fun GameDayPicker(
    channels: List<MediaItem>,
    initialLeft: MediaItem?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var left by remember { mutableStateOf(initialLeft) }
    var right by remember { mutableStateOf<MediaItem?>(null) }
    var selectingLeft by remember { mutableStateOf(initialLeft == null) }
    var query by remember { mutableStateOf("") }

    val filtered = remember(channels, query) {
        if (query.isBlank()) channels
        else channels.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.groupTitle?.contains(query, ignoreCase = true) == true
        }.take(120)
    }

    BackHandler { onDismiss() }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CinemaBg.copy(alpha = 0.96f))
                .padding(24.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "Dual Split Screen · Game Day",
                    color = OnCinema,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    "Pick two live channels. On TV, move with the remote and press Select.",
                    color = OnCinemaMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SideCard(
                        title = "LEFT",
                        channel = left,
                        active = selectingLeft,
                        modifier = Modifier.weight(1f),
                        onClick = { selectingLeft = true }
                    )
                    SideCard(
                        title = "RIGHT",
                        channel = right,
                        active = !selectingLeft,
                        modifier = Modifier.weight(1f),
                        onClick = { selectingLeft = false }
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(if (selectingLeft) "Search left channel" else "Search right channel")
                    }
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(filtered, key = { index, item -> "${item.id}-$index" }) { _, ch ->
                        val selected = (selectingLeft && left?.id == ch.id) ||
                            (!selectingLeft && right?.id == ch.id)
                        ChannelPickRow(
                            channel = ch,
                            selected = selected,
                            action = if (selectingLeft) "Pick Left" else "Pick Right",
                            onPick = {
                                if (selectingLeft) {
                                    left = ch
                                    if (right == null) selectingLeft = false
                                } else {
                                    right = ch
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PickerButton(label = "Cancel", filled = false, onClick = onDismiss)
                    Spacer(Modifier.width(12.dp))
                    val ready = left != null && right != null
                    PickerButton(
                        label = "Watch Both Games",
                        filled = true,
                        enabled = ready,
                        onClick = {
                            val l = left
                            val r = right
                            if (l != null && r != null) {
                                GameDayLauncher.start(context, l, r)
                                onDismiss()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SideCard(
    title: String,
    channel: MediaItem?,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) BrandBlue.copy(alpha = 0.18f) else CinemaSurface)
            .border(
                width = if (active || focused) 2.dp else 1.dp,
                color = if (active || focused) BrandBlue else CinemaSurfaceHigh,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .padding(12.dp)
    ) {
        Text(title, color = if (active) BrandBlue else OnCinemaMuted, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            channel?.name ?: "Select",
            color = OnCinema,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChannelPickRow(
    channel: MediaItem,
    selected: Boolean,
    action: String,
    onPick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> BrandBlue.copy(alpha = 0.35f)
                    focused -> CinemaSurfaceHigh
                    else -> CinemaSurface
                }
            )
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) BrandBlue else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onPick)
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(channel.name, color = OnCinema, maxLines = 1, overflow = TextOverflow.Ellipsis)
            channel.groupTitle?.let {
                Text(it, color = OnCinemaMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(action, color = BrandBlue, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PickerButton(
    label: String,
    filled: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        !enabled -> CinemaSurfaceHigh
        filled || focused -> BrandBlue
        else -> CinemaSurface
    }
    Text(
        text = label,
        color = if (enabled) Color.White else OnCinemaMuted,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(if (focused) 2.dp else 0.dp, Color.White, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}
