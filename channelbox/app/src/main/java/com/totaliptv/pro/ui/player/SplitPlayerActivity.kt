package com.totaliptv.pro.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem as CatalogItem
import com.totaliptv.pro.diagnostics.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Game Day: two built-in players side by side, edge to edge, one side of audio.
 * A failure on one side does not release the other player.
 */
@UnstableApi
class SplitPlayerActivity : ComponentActivity() {

    private enum class SideId { LEFT, RIGHT }

    private class Side(
        val id: SideId,
        var title: String,
        var originalUrl: String,
        var playbackUrl: String,
        var player: ExoPlayer? = null,
        var playerView: PlayerView? = null,
        var statusView: TextView? = null,
        var audioBadge: TextView? = null,
        var titleView: TextView? = null,
        var frame: FrameLayout? = null,
        var mediaId: String = "",
        var triedAlternate: Boolean = false,
        var preferSoftware: Boolean = false,
        var englishApplied: Boolean = false,
        var subsOn: Boolean = false,
        var resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
        var bufferJob: Job? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var left: Side
    private lateinit var right: Side
    private var activeAudio = SideId.LEFT
    private var controls: LinearLayout? = null
    private var soundLeftButton: Button? = null
    private var soundRightButton: Button? = null
    private var changeChannelButton: Button? = null
    private var aspectButton: Button? = null
    private var subsButton: Button? = null
    private var stopButton: Button? = null
    private var pickerHost: ComposeView? = null
    private var pickingSide by mutableStateOf<SideId?>(null)
    private var hideControlsJob: Job? = null
    private var released = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // System bars are hidden after setContentView. On API 30 the insets controller
        // NPEs inside the framework when the DecorView does not exist yet.

        val leftUrl = intent.getStringExtra(EXTRA_LEFT_URL).orEmpty()
        val rightUrl = intent.getStringExtra(EXTRA_RIGHT_URL).orEmpty()
        if (leftUrl.isBlank() || rightUrl.isBlank()) {
            finish()
            return
        }
        left = Side(
            id = SideId.LEFT,
            title = intent.getStringExtra(EXTRA_LEFT_TITLE) ?: "Left",
            originalUrl = leftUrl,
            playbackUrl = PlayerStream.preferredExoUrl(leftUrl, live = true)
        )
        left.mediaId = intent.getStringExtra(EXTRA_LEFT_ID).orEmpty()
        right = Side(
            id = SideId.RIGHT,
            title = intent.getStringExtra(EXTRA_RIGHT_TITLE) ?: "Right",
            originalUrl = rightUrl,
            playbackUrl = PlayerStream.preferredExoUrl(rightUrl, live = true)
        )
        right.mediaId = intent.getStringExtra(EXTRA_RIGHT_ID).orEmpty()
        if (savedInstanceState?.getString(STATE_AUDIO) == SideId.RIGHT.name) {
            activeAudio = SideId.RIGHT
        }

        setContentView(buildUi())
        enterImmersiveFullscreen()
        wireFocus()
        startSide(left)
        startSide(right)
        showControls()
        left.frame?.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        if (released) return
        left.player?.playWhenReady = true
        right.player?.playWhenReady = true
    }

    override fun onStop() {
        left.player?.playWhenReady = false
        right.player?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
        releaseBoth()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        enterImmersiveFullscreen()
        if (pickingSide == null && currentFocus == null && ::left.isInitialized) {
            sideFor(activeAudio).frame?.requestFocus()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        if (pickingSide != null) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                closePicker()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                stopSplit()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                openPicker(activeAudio)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> showControls()
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val wasHidden = controls?.visibility != View.VISIBLE
                showControls()
                val onSide = leftOrNull()?.frame?.hasFocus() == true || rightOrNull()?.frame?.hasFocus() == true
                if (wasHidden && onSide) {
                    soundLeftButton?.requestFocus()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> showControls()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setPadding(0, 0, 0, 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.BLACK)
            showDividers = LinearLayout.SHOW_DIVIDER_NONE
        }
        row.addView(buildHalf(left), halfLayoutParams())
        row.addView(buildHalf(right), halfLayoutParams())
        root.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        soundLeftButton = controlButton("Sound: Left") {
            selectSide(SideId.LEFT)
            left.frame?.requestFocus()
        }.also { buttons.addView(it) }
        soundRightButton = controlButton("Sound: Right") {
            selectSide(SideId.RIGHT)
            right.frame?.requestFocus()
        }.also { buttons.addView(it) }
        changeChannelButton = controlButton("Change channel") {
            openPicker(activeAudio)
        }.also { buttons.addView(it) }
        aspectButton = controlButton("Aspect") {
            cycleAspect(sideFor(activeAudio))
            showControls()
        }.also { buttons.addView(it) }
        subsButton = controlButton("Subtitles") {
            toggleSubtitles(sideFor(activeAudio))
            showControls()
        }.also { buttons.addView(it) }
        stopButton = controlButton("Stop split") { stopSplit() }.also { buttons.addView(it) }
        bar.addView(buttons)
        bar.addView(TextView(this).apply {
            text = "Left / Right selects a screen and its sound. OK or Menu changes that channel. Back stops both."
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        val barLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        root.addView(bar, barLp)
        controls = bar
        val host = ComposeView(this).apply {
            visibility = View.GONE
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { ChannelPickerOverlay() }
        }
        root.addView(host, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        pickerHost = host
        return root
    }

    private fun buildHalf(side: Side): FrameLayout {
        val frame = FrameLayout(this).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.BLACK)
            setPadding(0, 0, 0, 0)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                selectSide(side.id)
                openPicker(side.id)
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) selectSide(side.id) else paintControls()
            }
        }
        side.frame = frame
        val playerView = PlayerView(this).apply {
            useController = false
            resizeMode = side.resizeMode
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShutterBackgroundColor(Color.BLACK)
            setPadding(0, 0, 0, 0)
            isFocusable = false
        }
        frame.addView(playerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val title = TextView(this).apply {
            text = side.title
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 16f
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            setPadding(dp(12), dp(10), dp(12), dp(4))
            isFocusable = false
        }
        side.titleView = title
        frame.addView(title, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
        val badge = TextView(this).apply {
            text = "SOUND"
            setTextColor(Color.BLACK)
            setBackgroundColor(0xFFFFB300.toInt())
            setTypeface(typeface, Typeface.BOLD)
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
        }
        frame.addView(badge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { setMargins(0, dp(8), dp(8), 0) })
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
        }
        frame.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        side.playerView = playerView
        side.statusView = status
        side.audioBadge = badge
        return frame
    }

    private fun halfLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(0, 0, 0, 0)
        }
    }

    private fun startSide(side: Side) {
        try {
            startSideInner(side)
        } catch (t: Throwable) {
            DebugLog.append(this, TAG, "start ${side.id} failed", t)
            Log.e(TAG, "start ${side.id} failed", t)
            side.statusView?.text = "This side failed. The other keeps playing."
            side.statusView?.visibility = View.VISIBLE
        }
    }

    private fun startSideInner(side: Side) {
        side.bufferJob?.cancel()
        side.player?.let { existing ->
            existing.playWhenReady = false
            side.playerView?.player = null
            existing.release()
        }
        side.player = null
        side.englishApplied = false
        val exo = buildPlayer(side)
        side.player = exo
        side.playerView?.player = exo
        exo.setMediaItem(buildMediaItem(side.playbackUrl))
        exo.prepare()
        exo.playWhenReady = true
        applyVolumes()
        side.statusView?.text = "Starting ${side.title}…"
        side.statusView?.visibility = View.VISIBLE
        Log.i(TAG, "start ${side.id} url=${side.playbackUrl} software=${side.preferSoftware}")
    }

    private fun buildPlayer(side: Side): ExoPlayer {
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        if (side.preferSoftware) {
            renderers.setMediaCodecSelector(
                MediaCodecSelector { mimeType, secure, tunneling ->
                    MediaCodecSelector.DEFAULT
                        .getDecoderInfos(mimeType, secure, tunneling)
                        .sortedByDescending { it.softwareOnly }
                }
            )
        }
        val metrics = resources.displayMetrics
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setViewportSize(metrics.widthPixels / 2, metrics.heightPixels, /* orientationMayChange= */ false)
                .setAllowVideoMixedMimeTypeAdaptiveness(false)
                .setPreferredAudioLanguage("en")
                .setPreferredTextLanguage("en")
                .build()
        }
        val headers = linkedMapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        )
        PlayerStream.refererFor(side.playbackUrl)?.let { headers["Referer"] = it }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(PlayerStream.STREAM_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setKeepPostFor302Redirects(true)
            .setDefaultRequestProperties(headers)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                PlayerStream.SPLIT_MIN_BUFFER_MS,
                PlayerStream.SPLIT_MAX_BUFFER_MS,
                PlayerStream.SPLIT_PLAYBACK_BUFFER_MS,
                PlayerStream.SPLIT_REBUFFER_MS
            )
            .setTargetBufferBytes(PlayerStream.SPLIT_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false)
            .build()
        val exo = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(
                DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMinPlaybackSpeed(0.97f)
                    .setFallbackMaxPlaybackSpeed(1.03f)
                    .setMinUpdateIntervalMs(1_000)
                    .setTargetLiveOffsetIncrementOnRebufferMs(4_000)
                    .build()
            )
            .build()
        exo.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        exo.volume = if (side.id == activeAudio) 1f else 0f
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus= */ false
        )
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (side.player !== exo) return
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    scope.launch {
                        if (side.player !== exo) return@launch
                        side.statusView?.text = "Catching live edge…"
                        side.statusView?.visibility = View.VISIBLE
                        exo.seekToDefaultPosition()
                        exo.prepare()
                        exo.playWhenReady = true
                    }
                    return
                }
                val httpFail = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
                if (httpFail) {
                    scope.launch {
                        val flipped = tryAlternate(side, "Retrying this side…")
                        if (!flipped && side.player === exo) {
                            side.statusView?.text = "This side failed. The other keeps playing."
                            side.statusView?.visibility = View.VISIBLE
                        }
                    }
                    return
                }
                val decodeFail = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                if (decodeFail && !side.preferSoftware) {
                    side.preferSoftware = true
                    side.statusView?.text = "Decoder failed — retrying this side…"
                    side.statusView?.visibility = View.VISIBLE
                    scope.launch { startSide(side) }
                    return
                }
                side.statusView?.text = "This side failed. The other keeps playing."
                side.statusView?.visibility = View.VISIBLE
                Log.w(TAG, "side ${side.id} error ${error.errorCodeName}")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (side.player !== exo) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        side.statusView?.text = "Buffering ${side.title}…"
                        side.statusView?.visibility = View.VISIBLE
                        watchBuffer(side, exo)
                    }
                    Player.STATE_READY -> {
                        side.bufferJob?.cancel()
                        side.statusView?.text = ""
                        side.statusView?.visibility = View.GONE
                        if (runCatching { EnglishAudio.applyPreferred(exo) }.getOrDefault(false)) {
                            side.englishApplied = true
                        }
                        applyVolumes()
                    }
                    Player.STATE_ENDED -> {
                        side.statusView?.text = "${side.title} ended"
                        side.statusView?.visibility = View.VISIBLE
                    }
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (side.player !== exo) return
                if (!side.englishApplied &&
                    runCatching { EnglishAudio.applyPreferred(exo) }.getOrDefault(false)
                ) {
                    side.englishApplied = true
                    applyVolumes()
                }
            }
        })
        return exo
    }

    private fun watchBuffer(side: Side, exo: ExoPlayer) {
        side.bufferJob?.cancel()
        side.bufferJob = scope.launch {
            delay(PlayerStream.LIVE_STUCK_BUFFER_MS)
            if (side.player !== exo) return@launch
            if (exo.playbackState == Player.STATE_BUFFERING) {
                tryAlternate(side, "This side is stuck — trying the other URL…")
            }
        }
    }

    private fun tryAlternate(side: Side, reason: String): Boolean {
        if (side.triedAlternate) return false
        val alt = PlayerStream.alternateLiveUrl(side.playbackUrl, side.originalUrl) ?: return false
        side.triedAlternate = true
        side.playbackUrl = alt
        side.englishApplied = false
        val exo = side.player ?: return false
        side.statusView?.text = reason
        side.statusView?.visibility = View.VISIBLE
        exo.setMediaItem(buildMediaItem(alt))
        exo.prepare()
        exo.playWhenReady = true
        Log.i(TAG, "alternate ${side.id} -> $alt")
        return true
    }

    private fun buildMediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        PlayerStream.mimeForUrl(url)?.let { builder.setMimeType(it) }
        return builder.build()
    }

    private fun selectSide(side: SideId) {
        setActiveAudio(side)
    }

    private fun setActiveAudio(side: SideId) {
        activeAudio = side
        applyVolumes()
        showControls()
    }

    private fun openPicker(side: SideId) {
        if (released || !::left.isInitialized) return
        selectSide(side)
        pickingSide = side
        pickerHost?.visibility = View.VISIBLE
        pickerHost?.requestFocus()
        hideControlsJob?.cancel()
    }

    private fun closePicker() {
        val side = pickingSide
        pickingSide = null
        pickerHost?.visibility = View.GONE
        if (side != null && ::left.isInitialized) {
            sideFor(side).frame?.requestFocus()
        }
        showControls()
    }

    private fun onChannelPicked(side: SideId, item: CatalogItem) {
        closePicker()
        val app = application as? TotalIptvProApp
        val playable = app?.repository?.playableFrom(item) ?: item
        if (playable.streamUrl.isBlank()) {
            Toast.makeText(this, "That channel has no stream URL", Toast.LENGTH_SHORT).show()
            return
        }
        val target = sideFor(side)
        target.title = playable.name
        target.mediaId = playable.id
        target.originalUrl = playable.streamUrl
        target.playbackUrl = PlayerStream.preferredExoUrl(playable.streamUrl, live = true)
        target.triedAlternate = false
        target.preferSoftware = false
        target.englishApplied = false
        target.titleView?.text = playable.name
        startSide(target)
        target.frame?.requestFocus()
    }

    @Composable
    private fun ChannelPickerOverlay() {
        val side = pickingSide ?: return
        val app = application as? TotalIptvProApp
        val channels = androidx.compose.runtime.remember(app) {
            runCatching { app?.repository?.liveItems() }.getOrNull().orEmpty()
        }
        val categories = androidx.compose.runtime.remember(app) {
            runCatching { app?.repository?.categories(ContentKind.LIVE) }.getOrNull().orEmpty()
        }
        val favorites by (app?.repository?.favorites ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .collectAsState(initial = emptyList())
        val target = sideFor(side)
        GameDayChannelPicker(
            sideLabel = if (side == SideId.LEFT) "LEFT" else "RIGHT",
            channels = channels,
            categories = categories,
            favoriteIds = favorites.map { it.id }.toSet(),
            current = CatalogItem(
                id = target.mediaId.ifBlank { target.originalUrl },
                name = target.title,
                streamUrl = target.originalUrl,
                categoryId = null,
                kind = ContentKind.LIVE
            ),
            onPick = { onChannelPicked(side, it) },
            onBack = { closePicker() }
        )
    }

    private fun wireFocus() {
        val leftF = left.frame ?: return
        val rightF = right.frame ?: return
        val soundL = soundLeftButton ?: return
        val soundR = soundRightButton ?: return
        val change = changeChannelButton ?: return
        val aspect = aspectButton ?: return
        val subs = subsButton ?: return
        val stop = stopButton ?: return
        leftF.nextFocusRightId = rightF.id
        leftF.nextFocusDownId = soundL.id
        rightF.nextFocusLeftId = leftF.id
        rightF.nextFocusDownId = soundR.id
        soundL.nextFocusUpId = leftF.id
        soundL.nextFocusRightId = soundR.id
        soundL.nextFocusDownId = soundR.id
        soundR.nextFocusUpId = rightF.id
        soundR.nextFocusLeftId = soundL.id
        soundR.nextFocusDownId = change.id
        change.nextFocusUpId = soundR.id
        change.nextFocusDownId = aspect.id
        aspect.nextFocusUpId = change.id
        aspect.nextFocusDownId = subs.id
        subs.nextFocusUpId = aspect.id
        subs.nextFocusDownId = stop.id
        stop.nextFocusUpId = subs.id
    }

    private fun sideStroke(focused: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(if (focused) dp(5) else 0, 0xFFFFB300.toInt())
        }
    }

    private fun paintControls() {
        if (!::left.isInitialized) return
        fun paint(button: Button?, active: Boolean) {
            if (button == null) return
            val focused = button.isFocused
            button.setBackgroundColor(
                when {
                    focused -> 0xFFFFB300.toInt()
                    active -> 0xFF5C4A00.toInt()
                    else -> 0xFF1A1A1A.toInt()
                }
            )
            button.setTextColor(if (focused) Color.BLACK else Color.WHITE)
        }
        paint(soundLeftButton, activeAudio == SideId.LEFT)
        paint(soundRightButton, activeAudio == SideId.RIGHT)
        paint(changeChannelButton, false)
        paint(aspectButton, false)
        paint(subsButton, false)
        paint(stopButton, false)
        left.frame?.foreground = sideStroke(left.frame?.hasFocus() == true)
        right.frame?.foreground = sideStroke(right.frame?.hasFocus() == true)
    }

    private fun applyVolumes() {
        if (!::left.isInitialized) return
        left.player?.volume = if (activeAudio == SideId.LEFT) 1f else 0f
        right.player?.volume = if (activeAudio == SideId.RIGHT) 1f else 0f
        left.audioBadge?.visibility = if (activeAudio == SideId.LEFT) View.VISIBLE else View.GONE
        right.audioBadge?.visibility = if (activeAudio == SideId.RIGHT) View.VISIBLE else View.GONE
        paintControls()
    }

    private fun cycleAspect(side: Side) {
        val next = when (side.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        side.resizeMode = next
        side.playerView?.resizeMode = next
    }

    private fun toggleSubtitles(side: Side) {
        val exo = side.player ?: return
        side.subsOn = !side.subsOn
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setPreferredTextLanguage("en")
            .setSelectUndeterminedTextLanguage(side.subsOn)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !side.subsOn)
            .build()
        side.statusView?.text = if (side.subsOn) "Subtitles on" else "Subtitles off"
        side.statusView?.visibility = View.VISIBLE
    }

    private fun showControls() {
        controls?.visibility = View.VISIBLE
        hideControlsJob?.cancel()
        hideControlsJob = scope.launch {
            delay(6_000)
            controls?.visibility = View.GONE
        }
    }

    private fun stopSplit() {
        releaseBoth()
        finish()
    }

    private fun releaseBoth() {
        if (released) return
        released = true
        hideControlsJob?.cancel()
        for (side in listOf(leftOrNull(), rightOrNull())) {
            side ?: continue
            side.bufferJob?.cancel()
            side.playerView?.player = null
            side.player?.playWhenReady = false
            side.player?.release()
            side.player = null
        }
        scope.coroutineContext[Job]?.cancel()
    }

    private fun leftOrNull(): Side? = if (::left.isInitialized) left else null
    private fun rightOrNull(): Side? = if (::right.isInitialized) right else null

    private fun sideFor(id: SideId): Side = if (id == SideId.LEFT) left else right

    private fun controlButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            id = View.generateViewId()
            text = label
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(0xFF1A1A1A.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, _ -> paintControls() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Hide status and navigation bars. Touch decorView first so API 30 can create it
     * before [WindowCompat.getInsetsController] reads the window controller.
     */
    private fun enterImmersiveFullscreen() {
        try {
            val decor = window.decorView
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, decor)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Immersive fullscreen failed", t)
            DebugLog.append(this, TAG, "Immersive fullscreen failed", t)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::left.isInitialized) outState.putString(STATE_AUDIO, activeAudio.name)
    }

    companion object {
        private const val TAG = "TotalIPTV.Split"
        private const val STATE_AUDIO = "split_audio"
        const val EXTRA_LEFT_URL = "left_url"
        const val EXTRA_LEFT_TITLE = "left_title"
        const val EXTRA_LEFT_ID = "left_id"
        const val EXTRA_RIGHT_URL = "right_url"
        const val EXTRA_RIGHT_TITLE = "right_title"
        const val EXTRA_RIGHT_ID = "right_id"

        fun intent(context: Context, left: CatalogItem, right: CatalogItem): Intent {
            return Intent(context, SplitPlayerActivity::class.java).apply {
                putExtra(EXTRA_LEFT_URL, left.streamUrl)
                putExtra(EXTRA_LEFT_TITLE, left.name)
                putExtra(EXTRA_LEFT_ID, left.id)
                putExtra(EXTRA_RIGHT_URL, right.streamUrl)
                putExtra(EXTRA_RIGHT_TITLE, right.name)
                putExtra(EXTRA_RIGHT_ID, right.id)
            }
        }
    }
}
