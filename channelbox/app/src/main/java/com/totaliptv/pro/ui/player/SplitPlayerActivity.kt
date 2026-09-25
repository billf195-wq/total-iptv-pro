package com.totaliptv.pro.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
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
import com.totaliptv.pro.data.model.MediaItem as CatalogItem
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
        val title: String,
        val originalUrl: String,
        var playbackUrl: String,
        var player: ExoPlayer? = null,
        var playerView: PlayerView? = null,
        var statusView: TextView? = null,
        var audioBadge: TextView? = null,
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
    private var switchButton: Button? = null
    private var hideControlsJob: Job? = null
    private var released = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveFullscreen()

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
        right = Side(
            id = SideId.RIGHT,
            title = intent.getStringExtra(EXTRA_RIGHT_TITLE) ?: "Right",
            originalUrl = rightUrl,
            playbackUrl = PlayerStream.preferredExoUrl(rightUrl, live = true)
        )
        if (savedInstanceState?.getString(STATE_AUDIO) == SideId.RIGHT.name) {
            activeAudio = SideId.RIGHT
        }

        setContentView(buildUi())
        startSide(left)
        startSide(right)
        showControls()
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveFullscreen()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    setActiveAudio(SideId.LEFT)
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    setActiveAudio(SideId.RIGHT)
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    stopSplit()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_MENU -> {
                    if (controls?.visibility != View.VISIBLE) {
                        showControls()
                        return true
                    }
                }
            }
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
        val switchAudio = controlButton("Switch audio") {
            setActiveAudio(if (activeAudio == SideId.LEFT) SideId.RIGHT else SideId.LEFT)
            showControls()
        }
        switchButton = switchAudio
        buttons.addView(switchAudio)
        buttons.addView(controlButton("Aspect") {
            cycleAspect(sideFor(activeAudio))
            showControls()
        })
        buttons.addView(controlButton("Subtitles") {
            toggleSubtitles(sideFor(activeAudio))
            showControls()
        })
        buttons.addView(controlButton("Stop split") { stopSplit() })
        bar.addView(buttons)
        bar.addView(TextView(this).apply {
            text = "Left / Right moves audio. Tap a side on a phone. Back stops both."
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
        return root
    }

    private fun buildHalf(side: Side): FrameLayout {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setPadding(0, 0, 0, 0)
            isFocusable = false
            setOnClickListener {
                setActiveAudio(side.id)
                showControls()
            }
        }
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
        }
        frame.addView(title, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
        val badge = TextView(this).apply {
            text = "AUDIO"
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
                        if (EnglishAudio.applyPreferred(exo)) side.englishApplied = true
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
                if (!side.englishApplied && EnglishAudio.applyPreferred(exo)) {
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

    private fun setActiveAudio(side: SideId) {
        activeAudio = side
        applyVolumes()
        showControls()
    }

    private fun applyVolumes() {
        if (!::left.isInitialized) return
        left.player?.volume = if (activeAudio == SideId.LEFT) 1f else 0f
        right.player?.volume = if (activeAudio == SideId.RIGHT) 1f else 0f
        left.audioBadge?.visibility = if (activeAudio == SideId.LEFT) View.VISIBLE else View.GONE
        right.audioBadge?.visibility = if (activeAudio == SideId.RIGHT) View.VISIBLE else View.GONE
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
        val wasHidden = controls?.visibility != View.VISIBLE
        controls?.visibility = View.VISIBLE
        if (wasHidden) switchButton?.requestFocus()
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
            text = label
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun enterImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
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
