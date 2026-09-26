package com.totaliptv.pro.ui.epg

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.totaliptv.pro.data.LiveChannelMapping
import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.EpgChannelRow
import com.totaliptv.pro.data.model.EpgProgram
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.dvr.DvrRecordUi
import com.totaliptv.pro.ui.components.ClassicBrandBar
import com.totaliptv.pro.ui.components.NetworkImage
import com.totaliptv.pro.ui.components.SortChip
import com.totaliptv.pro.ui.components.TopBarChip
import com.totaliptv.pro.ui.player.GameDayPicker
import com.totaliptv.pro.util.SensitiveText
import com.totaliptv.pro.ui.theme.BrandBlue
import com.totaliptv.pro.ui.theme.CinemaBg
import com.totaliptv.pro.ui.theme.CinemaSurfaceHigh
import com.totaliptv.pro.ui.theme.CinemaSurface
import com.totaliptv.pro.ui.theme.FocusBorder
import com.totaliptv.pro.ui.theme.Hairline
import com.totaliptv.pro.ui.theme.SoftOverlay
import com.totaliptv.pro.ui.theme.LiveMarker
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.home.HomeShelfFit
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private val PX_PER_HOUR = GuideWindow.DP_PER_HOUR.dp
private val CHANNEL_COL = 168.dp
private val ROW_H = 56.dp
private const val GUIDE_ALL_ID = "__all_live__"

@OptIn(ExperimentalTvMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun EpgGuideScreen(
    repository: CatalogRepository,
    onPlay: (MediaItem) -> Unit,
    onBack: () -> Unit,
    initialCategoryId: String? = null,
    onCategoryChange: (String?) -> Unit = {},
    onRecordNow: ((MediaItem) -> Unit)? = null,
    onSchedule: ((MediaItem, EpgProgram) -> Unit)? = null,
    /** False when a parent shell already applied [HomeShelfFit.pageTopOffset]. */
    applyPageInset: Boolean = true
) {
    BackHandler { onBack() }

    val liveCategories: List<Category> = remember {
        repository.categories(ContentKind.LIVE)
    }
    val categoryCounts: Map<String, Int> = remember(liveCategories) {
        liveCategories.associate { it.id to repository.itemsForCategory(it.id).size }
    }
    val allLiveCount = remember { repository.liveItems().size }

    // Default ALL (or the Live pane category when Desktop shares filter state).
    var selectedCategoryId by remember {
        mutableStateOf(initialCategoryId?.takeIf { it.isNotBlank() } ?: GUIDE_ALL_ID)
    }
    LaunchedEffect(initialCategoryId) {
        val next = initialCategoryId?.takeIf { it.isNotBlank() } ?: GUIDE_ALL_ID
        if (selectedCategoryId != next) selectedCategoryId = next
    }
    var rows by remember { mutableStateOf<List<EpgChannelRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    // Controlled focus id for DPAD path — never trust Compose Surface focus steal.
    var focusedChannelId by remember { mutableStateOf<String?>(null) }
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val nowMs = remember { System.currentTimeMillis() }
    var cursorMs by remember { mutableLongStateOf(nowMs) }
    val windowStart = remember(nowMs) { GuideWindow.snapStart(nowMs) }
    val timelineWidthDp = (
        LocalConfiguration.current.screenWidthDp - CHANNEL_COL.value - 32f
    ).coerceAtLeast(GuideWindow.DP_PER_HOUR)
    val hourCount = GuideWindow.totalHours(timelineWidthDp)
    val windowEnd = GuideWindow.windowEndMs(windowStart, hourCount)
    val scroll = rememberScrollState()
    val listState = rememberLazyListState()

    val selectedCategoryName = remember(selectedCategoryId, liveCategories) {
        if (selectedCategoryId == GUIDE_ALL_ID) "ALL"
        else liveCategories.find { it.id == selectedCategoryId }?.name ?: "ALL"
    }

    val latestOnPlay by rememberUpdatedState(onPlay)
    val context = LocalContext.current
    val dvrSnap by remember(context) {
        (context.applicationContext as TotalIptvProApp).dvr.snapshot
    }.collectAsState()
    val focusedChannel = rows.find { it.channel.id == focusedChannelId }?.channel
    val recordLook = DvrRecordUi.appearance(dvrSnap.active, focusedChannel?.id, focusedChannel?.streamUrl)
    // Bumps on every category load so a stale click from a prior category cannot play.
    var guideLoadGen by remember { mutableStateOf(0) }
    var showGameDay by remember { mutableStateOf(false) }
    var bulkSettled by remember { mutableStateOf(false) }

    LaunchedEffect(selectedCategoryId) {
        // Always rebind EPG for the visible channel set on category chip change.
        bulkSettled = false
        val myGen = guideLoadGen + 1
        guideLoadGen = myGen
        status = "Loading guide… | $selectedCategoryName"
        scroll.scrollTo(0)
        focusedChannelId = null

        val filterCategoryId = selectedCategoryId.takeUnless { it == GUIDE_ALL_ID }
        val channels = LiveChannelMapping.filterLiveChannels(repository.liveItems(), filterCategoryId)
        if (channels.isEmpty()) {
            rows = emptyList()
            status = if (selectedCategoryId == GUIDE_ALL_ID) {
                "No live channels loaded."
            } else {
                "No channels in $selectedCategoryName."
            }
            loading = false
            bulkSettled = true
            return@LaunchedEffect
        }

        // Paint rows immediately (cache hits already have blocks). Play is allowed.
        rows = channels.map { repository.peekCachedGuideRow(it) ?: EpgChannelRow(channel = it) }
        loading = false
        if (guideLoadGen != myGen) return@LaunchedEffect

        if (!repository.hasXtreamEpg()) {
            status = "EPG requires an Xtream source. Showing channel list only. | $selectedCategoryName"
            bulkSettled = true
            return@LaunchedEffect
        }

        fun withDataCount() = rows.count { it.programs.isNotEmpty() || it.nowNext.now != null }
        val cachedN = withDataCount()
        status = if (cachedN > 0) {
            "Timeline | $cachedN channels | $selectedCategoryName | loading…"
        } else {
            "Loading programming… | $selectedCategoryName"
        }

        withContext(Dispatchers.IO) {
            runCatching { repository.prepareBulkGuideEpg(context.cacheDir) }
        }
        if (guideLoadGen != myGen) return@LaunchedEffect
        rows = channels.map { repository.peekCachedGuideRow(it) ?: EpgChannelRow(channel = it) }
        val n = withDataCount()
        status = if (n == 0) {
            "Timeline | $selectedCategoryName | loading visible rows…"
        } else {
            "Timeline | $n channels | $selectedCategoryName | ${timeFmt.format(Date())}"
        }
        bulkSettled = true
    }

    LaunchedEffect(selectedCategoryId, listState, bulkSettled) {
        if (!bulkSettled || !repository.hasXtreamEpg()) return@LaunchedEffect
        var batch: kotlinx.coroutines.Job? = null
        snapshotFlow {
            val first = listState.firstVisibleItemIndex.coerceAtLeast(0)
            val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(8)
            first to visible
        }
            .distinctUntilChanged()
            .debounce(GuideEpgLoad.SCROLL_DEBOUNCE_MS)
            .collect { (first, visible) ->
                val gen = guideLoadGen
                val channels = rows.map { it.channel }
                if (channels.isEmpty() || gen == 0) return@collect
                val already = channels.mapNotNull { ch ->
                    ch.id.takeIf { repository.peekCachedGuideRow(ch) != null }
                }.toSet()
                val order = GuideEpgLoad.fetchOrder(
                    channelIds = channels.map { it.id },
                    firstVisibleIndex = first,
                    visibleCount = visible,
                    focusedId = focusedChannelId,
                    alreadyStarted = already
                )
                if (order.isEmpty()) return@collect
                batch?.cancel()
                batch = launch {
                    val pending = ArrayDeque<EpgChannelRow>()
                    fun drain(): List<EpgChannelRow> = synchronized(pending) {
                        if (pending.isEmpty()) emptyList() else pending.toList().also { pending.clear() }
                    }
                    fun paint(snap: List<EpgChannelRow>) {
                        if (snap.isEmpty() || guideLoadGen != gen) return
                        var next = rows
                        for (filled in snap) next = GuideEpgLoad.applyRow(next, filled)
                        rows = next
                        val n = rows.count { it.programs.isNotEmpty() || it.nowNext.now != null }
                        status = "Timeline | $n / ${channels.size} | $selectedCategoryName"
                    }
                    val flusher = launch {
                        while (isActive) {
                            delay(GuideEpgLoad.UI_BATCH_MS)
                            paint(drain())
                        }
                    }
                    supervisorScope {
                        for (idx in order) {
                            val ch = channels.getOrNull(idx) ?: continue
                            launch {
                                val filled = withContext(Dispatchers.IO) {
                                    try {
                                        repository.loadGuideRow(ch)
                                    } catch (ce: CancellationException) {
                                        throw ce
                                    } catch (_: Throwable) {
                                        EpgChannelRow(channel = ch)
                                    }
                                }
                                if (!isActive || guideLoadGen != gen) return@launch
                                val nowFirst = listState.firstVisibleItemIndex.coerceAtLeast(0)
                                val nowVisible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(8)
                                val stillNear = idx in nowFirst until (nowFirst + nowVisible + GuideEpgLoad.BUFFER_ROWS)
                                if (!stillNear) return@launch
                                synchronized(pending) { pending.add(filled) }
                            }
                        }
                    }
                    flusher.cancel()
                    paint(drain())
                }
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CinemaBg)
    ) {
        if (applyPageInset) {
            ClassicBrandBar()
        }
        Row(
            modifier = Modifier.padding(
                start = if (applyPageInset) 12.dp else 0.dp,
                top = if (applyPageInset) HomeShelfFit.pageTopOffset else 0.dp,
                end = if (applyPageInset) 12.dp else 0.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopBarChip(label = "← Back", onClick = onBack, emphasized = true)
            Spacer(Modifier.width(12.dp))
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnCinemaMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            run {
                    val row = rows.find { it.channel.id == focusedChannelId }
                    val programs = row?.let {
                        if (it.programs.isNotEmpty()) it.programs
                        else listOfNotNull(it.nowNext.now, it.nowNext.next)
                    }.orEmpty()
                    val prog = GuideCursor.programAt(programs, cursorMs)
                    if (prog != null) {
                        Text(
                            "${prog.title}  ${timeFmt.format(Date(prog.startMs))}–${timeFmt.format(Date(prog.endMs))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnCinema,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
            }
            TopBarChip(
                label = recordLook.label,
                active = recordLook.selected,
                emphasized = true,
                onClick = {
                    val ch = rows.find { it.channel.id == focusedChannelId }?.channel
                    if (ch != null) {
                        val nowProg = rows.find { it.channel.id == ch.id }?.let { row ->
                            row.nowNext.now ?: row.programs.find { p -> p.contains(System.currentTimeMillis()) }
                        }
                        if (onRecordNow != null) onRecordNow(ch)
                        else com.totaliptv.pro.dvr.DvrActions.recordNow(
                            context,
                            ch,
                            nowProg?.title,
                            nowProg?.endMs
                        )
                    } else {
                        Toast.makeText(context, "Focus a channel, then Record", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            TopBarChip(
                label = "Split",
                emphasized = true,
                onClick = { showGameDay = true }
            )
            Spacer(Modifier.width(8.dp))
            TopBarChip(
                label = "Schedule",
                onClick = {
                    val row = rows.find { it.channel.id == focusedChannelId }
                    val ch = row?.channel
                    val next = row?.nowNext?.next ?: row?.programs?.firstOrNull {
                        it.startMs > System.currentTimeMillis()
                    }
                    if (ch != null && next != null) {
                        if (onSchedule != null) onSchedule(ch, next)
                        else com.totaliptv.pro.dvr.DvrActions.schedule(
                            context, ch, next.title, next.startMs, next.endMs
                        )
                    } else {
                        Toast.makeText(context, "Focus a channel with upcoming EPG to schedule", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        // Live category picker (same categories as Live TV hub)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (applyPageInset) 16.dp else 0.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
        ) {
            item(key = GUIDE_ALL_ID) {
                SortChip(
                    label = "ALL ($allLiveCount)",
                    selected = selectedCategoryId == GUIDE_ALL_ID,
                    onClick = {
                        selectedCategoryId = GUIDE_ALL_ID
                        onCategoryChange(null)
                    }
                )
            }
            items(liveCategories, key = { it.id }) { cat ->
                val count = categoryCounts[cat.id]
                SortChip(
                    label = if (count != null) "${cat.name} ($count)" else cat.name,
                    selected = selectedCategoryId == cat.id,
                    onClick = {
                        selectedCategoryId = cat.id
                        onCategoryChange(cat.id)
                    }
                )
            }
        }

        when {
            loading -> Text(
                "Loading EPG…",
                modifier = Modifier.padding(28.dp),
                color = OnCinemaMuted
            )
            rows.isEmpty() -> Text(
                status ?: "No channels.",
                modifier = Modifier.padding(28.dp),
                color = OnCinemaMuted
            )
            else -> {
                // Hour headers
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(CHANNEL_COL))
                    Row(modifier = Modifier.horizontalScroll(scroll)) {
                        for (h in 0 until hourCount) {
                            val t = windowStart + h * 60 * 60 * 1000L
                            Text(
                                text = timeFmt.format(Date(t)),
                                style = MaterialTheme.typography.labelMedium,
                                color = OnCinemaMuted,
                                modifier = Modifier
                                    .width(PX_PER_HOUR)
                                    .padding(start = 4.dp, bottom = 6.dp)
                            )
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(rows, key = { "${selectedCategoryId}:${it.channel.id}" }) { row ->
                        TimelineRow(
                            row = row,
                            hourCount = hourCount,
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            nowMs = nowMs,
                            timeFmt = timeFmt,
                            scrollState = scroll,
                            isFocusedRow = focusedChannelId == row.channel.id,
                            cursorMs = cursorMs,
                            onMoveCursor = { cursorMs = it },
                            onFocused = { id, focused ->
                                if (focused) focusedChannelId = id
                                else if (focusedChannelId == id) focusedChannelId = null
                            },
                            onPlayChannel = { channel, source ->
                                if (loading) {
                                    Log.w(
                                        "TotalIPTV.Guide",
                                        "guidePlayBlocked loading src=$source name=${channel.name} id=${channel.id}"
                                    )
                                } else {
                                    // Play the row's MediaItem — never an index into another list.
                                    val playable = repository.playableFrom(channel)
                                    val nameOk = playable.name.equals(channel.name, ignoreCase = true)
                                    val sidOk = playable.xtreamStreamId == channel.xtreamStreamId ||
                                        channel.xtreamStreamId == null
                                    if (!nameOk || !sidOk) {
                                        Log.e(
                                            "TotalIPTV.Guide",
                                            "guidePlayMISMATCH src=$source displayed=${channel.name} " +
                                                "dispSid=${channel.xtreamStreamId} -> play=${playable.name} " +
                                                "playSid=${playable.xtreamStreamId} id=${playable.id}"
                                        )
                                        Toast.makeText(
                                            context,
                                            "Guide mismatch: ${channel.name} → ${playable.name}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        Log.i(
                                            "TotalIPTV.Guide",
                                            "guidePlay src=$source displayed=${channel.name} id=${playable.id} " +
                                                "sid=${playable.xtreamStreamId} num=${playable.channelNum} url=${SensitiveText.redact(playable.streamUrl)}"
                                        )
                                    }
                                    latestOnPlay(playable)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    if (showGameDay) {
        GameDayPicker(
            channels = repository.liveItems(),
            initialLeft = focusedChannel,
            onDismiss = { showGameDay = false }
        )
    }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TimelineRow(
    row: EpgChannelRow,
    hourCount: Int,
    windowStart: Long,
    windowEnd: Long,
    nowMs: Long,
    timeFmt: SimpleDateFormat,
    scrollState: androidx.compose.foundation.ScrollState,
    isFocusedRow: Boolean,
    cursorMs: Long,
    onMoveCursor: (Long) -> Unit,
    onFocused: (id: String, focused: Boolean) -> Unit,
    onPlayChannel: (channel: MediaItem, source: String) -> Unit
) {
    // Snapshot this row's MediaItem for the composition slot — play never uses list index
    // or a neighbor row's identity (label and stream stay glued together).
    val rowChannel = row.channel
    val channelId = rowChannel.id
    val channelName = rowChannel.name
    val channelSid = rowChannel.xtreamStreamId
    val channelNum = rowChannel.channelNum
    var locallyFocused by remember(channelId) { mutableStateOf(false) }
    val focused = locallyFocused || isFocusedRow
    val latestPlay by rememberUpdatedState(onPlayChannel)
    val latestFocus by rememberUpdatedState(onFocused)
    val latestCursor by rememberUpdatedState(onMoveCursor)
    val rowScope = rememberCoroutineScope()
    val density = LocalDensity.current
    var lastClickMs by remember(channelId) { mutableStateOf(0L) }

    fun firePlay(source: String) {
        val channel = rowChannel // same instance shown in the channel label column
        val now = System.currentTimeMillis()
        if (now - lastClickMs < 450L) {
            Log.i("TotalIPTV.Guide", "guideDebounce src=$source id=${channel.id} name=${channel.name}")
            return
        }
        lastClickMs = now
        Log.i(
            "TotalIPTV.Guide",
            "guideClick src=$source name=${channel.name} id=${channel.id} sid=${channel.xtreamStreamId} num=${channel.channelNum}"
        )
        latestPlay(channel, source)
    }

    val programs: List<EpgProgram> = when {
        row.programs.isNotEmpty() -> row.programs
        else -> listOfNotNull(row.nowNext.now, row.nowNext.next)
    }
    val windowMs = (windowEnd - windowStart).coerceAtLeast(1L)
    val totalWidthDp = PX_PER_HOUR * hourCount.coerceAtLeast(1)
    val nowFraction = ((nowMs - windowStart).toFloat() / windowMs.toFloat()).coerceIn(0f, 1f)

    // NO TV Surface(onClick) — that API plays the *focused* neighbor on emulator mouse.
    // Play only via pointer hit-test or DPAD/Enter on this focusable row.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (focused) BrandBlue.copy(alpha = 0.14f) else CinemaSurface
            )
            .then(
                if (focused) Modifier.border(2.dp, FocusBorder, RoundedCornerShape(8.dp))
                else Modifier
            )
            .onFocusChanged { fs ->
                locallyFocused = fs.isFocused
                latestFocus(channelId, fs.isFocused)
            }
            .focusable()
            .onKeyEvent { e ->
                val dir = when (e.key) {
                    Key.DirectionLeft, Key.MediaRewind, Key.MediaSkipBackward -> -1
                    Key.DirectionRight, Key.MediaFastForward, Key.MediaSkipForward -> 1
                    else -> 0
                }
                if (dir != 0 && e.type == KeyEventType.KeyDown) {
                    val next = GuideCursor.step(programs, cursorMs, dir, windowStart, windowEnd)
                    latestCursor(next)
                    val widthPx = with(density) { totalWidthDp.toPx() }.toInt().coerceAtLeast(1)
                    val px = GuideCursor.scrollOffsetPx(next, windowStart, windowMs, widthPx)
                    rowScope.launch {
                        scrollState.scrollTo(px.coerceAtMost(scrollState.maxValue.coerceAtLeast(0)))
                    }
                    return@onKeyEvent true
                }
                if (e.type != KeyEventType.KeyUp) return@onKeyEvent false
                val isActivate =
                    e.key == Key.DirectionCenter ||
                        e.key == Key.Enter ||
                        e.key == Key.NumPadEnter
                if (!isActivate) return@onKeyEvent false
                firePlay("dpad")
                true
            }
            .pointerInput(channelId) {
                detectTapGestures(
                    onTap = {
                        firePlay("pointer")
                    }
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ROW_H)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .width(CHANNEL_COL)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0C1018)),
                    contentAlignment = Alignment.Center
                ) {
                    NetworkImage(
                        url = com.totaliptv.pro.data.LogoUrls.forPlayback(row.channel.streamUrl, row.channel.logoUrl),
                        contentDescription = row.channel.name,
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        contentScale = ContentScale.Fit,
                        placeholderLabel = row.channel.name.take(1).uppercase(),
                        decodeWidth = 96,
                        decodeHeight = 96
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    row.channel.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = OnCinema,
                    modifier = Modifier.weight(1f)
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(scrollState)
            ) {
                Box(
                    modifier = Modifier
                        .width(totalWidthDp)
                        .fillMaxHeight()
                ) {
                    // Program blocks
                    programs.forEach { prog ->
                        val start = max(prog.startMs, windowStart)
                        val end = minOf(prog.endMs, windowEnd)
                        if (end <= start) return@forEach
                        val leftFrac = (start - windowStart).toFloat() / windowMs
                        val widthFrac = (end - start).toFloat() / windowMs
                        val isLive = prog.contains(nowMs)
                        val selected = focused &&
                            GuideCursor.programAt(programs, cursorMs)?.let {
                                it.startMs == prog.startMs && it.endMs == prog.endMs
                            } == true
                        Box(
                            modifier = Modifier
                                .offset(x = totalWidthDp * leftFrac)
                                .width(totalWidthDp * widthFrac - 2.dp)
                                .fillMaxHeight()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        selected -> BrandBlue.copy(alpha = 0.55f)
                                        isLive -> BrandBlue.copy(alpha = 0.28f)
                                        else -> CinemaSurfaceHigh
                                    }
                                )
                                .border(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected || isLive) BrandBlue.copy(alpha = 0.9f)
                                    else SoftOverlay,
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Column {
                                Text(
                                    text = prog.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = OnCinema,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${timeFmt.format(Date(prog.startMs))}-${timeFmt.format(Date(prog.endMs))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnCinemaMuted,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // Live marker line
                    if (nowFraction in 0f..1f) {
                        Box(
                            modifier = Modifier
                                .offset(x = totalWidthDp * nowFraction)
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(LiveMarker)
                        )
                    }
                }
            }
        }
    }
}
