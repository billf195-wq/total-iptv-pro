package com.totaliptv.pro.ui.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.ui.components.DpadSearchField
import com.totaliptv.pro.ui.components.isTelevisionUi
import com.totaliptv.pro.data.LiveChannelMapping
import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.MediaItem
import kotlinx.coroutines.flow.flowOf

/**
 * Desktop Game Day assigns a channel to the LEFT screen and a different channel
 * to the RIGHT screen. This filter is the list behind one of those slots.
 */
object GameDayChannels {
    const val FAVORITES = "gameday-favorites"
    const val RESULT_LIMIT = 200

    fun filter(
        channels: List<MediaItem>,
        favoriteIds: Set<String>,
        categoryId: String?,
        query: String,
        limit: Int = RESULT_LIMIT
    ): List<MediaItem> {
        val liveCategory = if (categoryId == FAVORITES) null else categoryId
        val matched = LiveChannelMapping.filterLiveChannels(channels, liveCategory, query)
        val scoped = if (categoryId == FAVORITES) {
            matched.filter { it.id in favoriteIds }
        } else {
            matched
        }
        return if (limit > 0) scoped.take(limit) else scoped
    }

    /** Assigning one side never clears the other. */
    fun assign(
        left: MediaItem?,
        right: MediaItem?,
        side: String,
        picked: MediaItem
    ): Pair<MediaItem?, MediaItem?> {
        return if (side == "LEFT") picked to right else left to picked
    }

    fun canStart(left: MediaItem?, right: MediaItem?): Boolean {
        return !left?.streamUrl.isNullOrBlank() && !right?.streamUrl.isNullOrBlank()
    }
}

object GameDayLauncher {
    fun start(context: Context, left: MediaItem, right: MediaItem) {
        val app = context.applicationContext as? TotalIptvProApp
        val playableLeft = app?.repository?.playableFrom(left) ?: left
        val playableRight = app?.repository?.playableFrom(right) ?: right
        if (playableLeft.streamUrl.isBlank() || playableRight.streamUrl.isBlank()) {
            Toast.makeText(context, "Both channels need a stream URL", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            context.startActivity(SplitPlayerActivity.intent(context, playableLeft, playableRight))
        } catch (e: ActivityNotFoundException) {
            // Phone builds remove SplitPlayerActivity from the manifest.
            Toast.makeText(context, "Split screen isn't in this app", Toast.LENGTH_SHORT).show()
        }
    }
}

private val PickerBlack = Color(0xFF000000)
private val PickerCard = Color(0xFF141414)
private val PickerFocus = Color(0xFFFFB300)
private val PickerText = Color(0xFFF5F5F5)
private val PickerMuted = Color(0xFFB0B0B0)

/**
 * Setup screen: two slots. OK on a slot opens that side's channel picker.
 * Start plays both. Back closes setup.
 */
@Composable
fun GameDayPicker(
    channels: List<MediaItem>,
    initialLeft: MediaItem?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as? TotalIptvProApp
    val categories = remember(app) {
        runCatching { app?.repository?.categories(com.totaliptv.pro.data.model.ContentKind.LIVE) }
            .getOrNull()
            .orEmpty()
    }
    val favorites by (app?.repository?.favorites ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }

    var left by remember { mutableStateOf(initialLeft) }
    var right by remember { mutableStateOf<MediaItem?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    val leftFocus = remember { FocusRequester() }
    val startFocus = remember { FocusRequester() }

    BackHandler(enabled = picking == null) { onDismiss() }

    MaterialTheme(colorScheme = darkColorScheme(background = PickerBlack, surface = PickerCard)) {
        Box(Modifier.fillMaxSize().background(PickerBlack)) {
            if (picking == null) {
                SetupSlots(
                    left = left,
                    right = right,
                    leftFocus = leftFocus,
                    startFocus = startFocus,
                    onPickLeft = { picking = "LEFT" },
                    onPickRight = { picking = "RIGHT" },
                    onStart = {
                        val l = left
                        val r = right
                        if (l != null && r != null && GameDayChannels.canStart(l, r)) {
                            GameDayLauncher.start(context, l, r)
                            onDismiss()
                        }
                    },
                    onCancel = onDismiss
                )
                LaunchedEffect(Unit) {
                    runCatching { leftFocus.requestFocus() }
                }
            } else {
                val side = picking ?: "LEFT"
                GameDayChannelPicker(
                    sideLabel = side,
                    channels = channels,
                    categories = categories,
                    favoriteIds = favoriteIds,
                    current = if (side == "LEFT") left else right,
                    onPick = { item ->
                        val assigned = GameDayChannels.assign(left, right, side, item)
                        left = assigned.first
                        right = assigned.second
                        picking = null
                    },
                    onBack = { picking = null }
                )
            }
        }
    }
}

@Composable
private fun SetupSlots(
    left: MediaItem?,
    right: MediaItem?,
    leftFocus: FocusRequester,
    startFocus: FocusRequester,
    onPickLeft: () -> Unit,
    onPickRight: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp)
    ) {
        Text(
            "Dual Split Screen · Game Day",
            color = PickerText,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Choose a channel for the left screen and a channel for the right screen, then Start.",
            color = PickerMuted,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Remote: move to a screen, press OK to open its channel list. Back cancels.",
            color = PickerMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SlotCard(
                title = "LEFT",
                channel = left,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(leftFocus)
                    .focusProperties { down = startFocus },
                onClick = onPickLeft
            )
            SlotCard(
                title = "RIGHT",
                channel = right,
                modifier = Modifier
                    .weight(1f)
                    .focusProperties { down = startFocus },
                onClick = onPickRight
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FocusTextButton(label = "Cancel", filled = false, onClick = onCancel)
            Spacer(Modifier.width(12.dp))
            val ready = GameDayChannels.canStart(left, right)
            FocusTextButton(
                label = "Start",
                filled = true,
                enabled = ready,
                modifier = Modifier.focusRequester(startFocus).focusProperties { up = leftFocus },
                onClick = onStart
            )
        }
    }
}

@Composable
private fun SlotCard(
    title: String,
    channel: MediaItem?,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 4.dp else 1.dp,
                color = if (focused) PickerFocus else Color(0xFF333333),
                shape = RoundedCornerShape(12.dp)
            )
            .background(if (focused) Color(0xFF1C1C1C) else PickerCard, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Text(title, color = if (focused) PickerFocus else PickerMuted, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            channel?.name ?: "Choose channel",
            color = PickerText,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (focused) "Press OK" else "Select",
            color = PickerMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Channel list for one side. Search, categories, and favorites.
 * Back returns to the setup screen or the running split without changing the channel.
 */
@Composable
fun GameDayChannelPicker(
    sideLabel: String,
    channels: List<MediaItem>,
    categories: List<Category>,
    favoriteIds: Set<String>,
    current: MediaItem? = null,
    onPick: (MediaItem) -> Unit,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<String?>(null) }
    val allFocus = remember { FocusRequester() }
    val filtered = remember(channels, favoriteIds, categoryId, query) {
        GameDayChannels.filter(channels, favoriteIds, categoryId, query)
    }
    val truncated = filtered.size >= GameDayChannels.RESULT_LIMIT

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(PickerBlack)
            .padding(24.dp)
    ) {
        Text(
            "Choose $sideLabel channel",
            color = PickerText,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            current?.let { "Now: ${it.name}" } ?: "Remote: move, press OK to choose. Back returns.",
            color = PickerMuted
        )
        Spacer(Modifier.height(10.dp))
        if (isTelevisionUi()) {
            DpadSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search",
                textStyle = androidx.compose.ui.text.TextStyle(color = PickerText),
                placeholderColor = PickerMuted,
                cursorColor = PickerFocus,
                backgroundColor = Color(0xFF141414),
                focusedBorderColor = PickerFocus,
                idleBorderColor = Color(0xFF444444),
                downFocus = allFocus
            )
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PickerText,
                    unfocusedTextColor = PickerText,
                    focusedBorderColor = PickerFocus,
                    unfocusedBorderColor = Color(0xFF444444),
                    focusedLabelColor = PickerFocus,
                    unfocusedLabelColor = PickerMuted,
                    cursorColor = PickerFocus
                )
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                CategoryChip(
                    label = "All",
                    selected = categoryId == null,
                    modifier = Modifier.focusRequester(allFocus),
                    onClick = { categoryId = null }
                )
            }
            item {
                CategoryChip(
                    label = "Favorites",
                    selected = categoryId == GameDayChannels.FAVORITES,
                    onClick = { categoryId = GameDayChannels.FAVORITES }
                )
            }
            items(categories, key = { it.id }) { cat ->
                CategoryChip(
                    label = cat.name,
                    selected = categoryId == cat.id,
                    onClick = { categoryId = cat.id }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (truncated) {
            Text("Showing the first ${GameDayChannels.RESULT_LIMIT}. Search or pick a category.", color = PickerMuted)
            Spacer(Modifier.height(4.dp))
        }
        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (categoryId == GameDayChannels.FAVORITES) "No favorite channels." else "No channels.",
                    color = PickerMuted
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(filtered, key = { it.id }) { ch ->
                    ChannelPickRow(
                        channel = ch,
                        selected = current?.id == ch.id,
                        onPick = { onPick(ch) }
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        runCatching { allFocus.requestFocus() }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = label,
        color = if (focused || selected) Color.Black else PickerText,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Color.White else Color(0xFF333333),
                shape = RoundedCornerShape(20.dp)
            )
            .background(
                when {
                    focused -> PickerFocus
                    selected -> Color(0xFF8A6A00)
                    else -> PickerCard
                },
                RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
private fun ChannelPickRow(
    channel: MediaItem,
    selected: Boolean,
    onPick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) PickerFocus else Color.Transparent
            )
            .background(
                when {
                    focused -> Color(0xFF2A2208)
                    selected -> Color(0xFF1A1A1A)
                    else -> PickerCard
                }
            )
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(channel.name, color = PickerText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val group = channel.groupTitle ?: channel.categoryId
            if (!group.isNullOrBlank()) {
                Text(group, color = PickerMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            if (focused) "OK" else if (selected) "Selected" else "",
            color = PickerFocus,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun FocusTextButton(
    label: String,
    filled: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        !enabled -> Color(0xFF222222)
        focused || filled -> PickerFocus
        else -> PickerCard
    }
    Text(
        text = label,
        color = if (!enabled) PickerMuted else if (focused || filled) Color.Black else PickerText,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Color.White else Color(0xFF333333),
                shape = RoundedCornerShape(8.dp)
            )
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp)
    )
}
