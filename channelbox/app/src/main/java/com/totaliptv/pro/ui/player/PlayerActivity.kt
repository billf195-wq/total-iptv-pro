package com.totaliptv.pro.ui.player

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import com.totaliptv.pro.data.local.PreferredPlayer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.local.AppPreferences
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.WatchProgress
import com.totaliptv.pro.data.local.WatchProgressStore
import com.totaliptv.pro.data.model.EpgNowNext
import com.totaliptv.pro.diagnostics.DebugLog
import com.totaliptv.pro.util.SensitiveText
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ID = "id"
        const val EXTRA_KIND = "kind"
        const val EXTRA_LOGO = "logo"
        /** When true, ignore saved resume and start at 0 (and clear progress). */
        const val EXTRA_START_OVER = "start_over"
        /** Catalog parent id (series-123) when playing an episode leaf. */
        const val EXTRA_CATALOG_ID = "catalog_id"

        private val ENGLISH_CODES = setOf("eng", "en", "english", "en-us", "en-gb", "en_us", "en_gb")
        private val VLC_PACKAGES = listOf("org.videolan.vlc", "org.videolan.vlc.debug")
    }

    data class AudioTrackOption(
        val groupIndex: Int,
        val trackIndex: Int,
        val group: TrackGroup,
        val label: String,
        val language: String?,
        val selected: Boolean,
        val isEnglish: Boolean,
        val channelCount: Int,
        val isDefault: Boolean,
        val supported: Boolean,
        val secondaryAudio: Boolean
    ) {
        /** True when codecs report real channels, or channel count is unknown (common before decode). */
        val hasPlayableChannels: Boolean
            get() = channelCount == Format.NO_VALUE || channelCount > 0
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var statusView: TextView? = null
    private var titleView: TextView? = null
    private var epgView: TextView? = null
    private var audioLabelView: TextView? = null
    private var overlay: LinearLayout? = null
    private var playNextButton: Button? = null
    private var recordButton: Button? = null
    private var cachedNextEpisode: com.totaliptv.pro.data.model.MediaItem? = null
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, error ->
            showPlaybackFailure(error)
        }
    )
    private var streamUrl: String = ""
    private var mediaId: String = ""
    private var mediaTitle: String = ""
    private var mediaKind: ContentKind = ContentKind.LIVE
    private var mediaLogo: String? = null
    private var retryCount = 0
    /** Once true, rebuild ExoPlayer with software-preferring MediaCodecSelector. */
    private var preferSoftwareDecoders = false
    private val englishAutoApplied = AtomicBoolean(false)
    private var hideOverlayJob: Job? = null
    private var progressSaveJob: Job? = null
    private var resumeApplied = false
    private var resumeInProgress = false
    private var resumeWaitAttempts = 0
    private var startOver = false
    /** One VOD retry without a forced MIME type when the URL extension is wrong. */
    private var omitForcedMime = false
    private var catalogId: String? = null
    private lateinit var watchProgressStore: WatchProgressStore
    /** Actual URI passed to ExoPlayer (live prefers .ts; may flip to .m3u8). */
    private var playbackUrl: String = ""
    private var triedAlternateLiveUrl = false
    private var bufferWatchJob: Job? = null
    private var aspectRatioBtn: Button? = null
    private var currentResizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var autoPipEnabled: Boolean = AppPreferences.defaultAutoPip()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phone builds omit manifest screenOrientation. Lock landscape only when the
        // platform will accept it, so Android 8.0 does not kill the process here.
        lockLandscapeIfSafe()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // System bars are hidden after setContentView. On API 30 the insets controller
        // NPEs inside the framework when the DecorView does not exist yet.

        streamUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        mediaTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Playback"
        mediaId = intent.getStringExtra(EXTRA_ID) ?: streamUrl
        mediaKind = runCatching {
            ContentKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: ContentKind.LIVE.name)
        }.getOrDefault(ContentKind.LIVE)
        mediaLogo = intent.getStringExtra(EXTRA_LOGO)
        // Re-bind via playableFrom so Xtream LIVE URL is rebuilt from stream_id (never num / stale extra).
        watchProgressStore = (application as TotalIptvProApp).watchProgress
        scope.launch(Dispatchers.IO) {
            val enabled = runCatching {
                (application as TotalIptvProApp).preferences.getAutoPip()
            }.getOrDefault(AppPreferences.defaultAutoPip())
            withContext(Dispatchers.Main) { autoPipEnabled = enabled }
        }
        startOver = intent.getBooleanExtra(EXTRA_START_OVER, false)
        catalogId = intent.getStringExtra(EXTRA_CATALOG_ID)?.takeIf { it.isNotBlank() }
        // Fallbacks so Continue watching / detail Resume can map posters by catalog id.
        if (catalogId.isNullOrBlank()) {
            catalogId = when {
                mediaId.startsWith("vod-") -> mediaId
                mediaId.startsWith("series-") && !mediaId.startsWith("series-ep-") -> mediaId
                else -> null
            }
        }
        runCatching {
            val app = application as TotalIptvProApp
            val seed = app.repository.itemById(mediaId)
                ?: com.totaliptv.pro.data.model.MediaItem(
                    id = mediaId,
                    name = mediaTitle,
                    streamUrl = streamUrl,
                    categoryId = null,
                    kind = mediaKind,
                    logoUrl = mediaLogo
                )
            val canonical = app.repository.playableFrom(seed)
            if (canonical.streamUrl.isNotBlank()) {
                streamUrl = canonical.streamUrl
                if (canonical.name.isNotBlank()) mediaTitle = canonical.name
                mediaKind = canonical.kind
                mediaLogo = canonical.logoUrl ?: canonical.posterUrl ?: mediaLogo
                mediaId = canonical.id
            }
            if (catalogId.isNullOrBlank()) {
                catalogId = when {
                    mediaId.startsWith("vod-") -> mediaId
                    mediaId.startsWith("series-") && !mediaId.startsWith("series-ep-") -> mediaId
                    else -> catalogId
                }
            }
            Log.i(
                "TotalIPTV.Live",
                "playerStart name=$mediaTitle id=$mediaId sid=${canonical.xtreamStreamId} num=${canonical.channelNum} url=${SensitiveText.redact(streamUrl)}"
            )
        }

        val root = FrameLayout(this)
        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 3500
            setShowNextButton(false)
            setShowPreviousButton(false)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    // Keep custom controls (incl. Play next) in sync with Shield OSD / seek bar.
                    if (visibility == View.VISIBLE) {
                        refreshPlayNextButton()
                        showOverlayTemporarily()
                    } else {
                        hideOverlayIfIdle()
                    }
                }
            )
        }
        root.addView(playerView)

        val display = resources.displayMetrics
        val overscanX = NextEpisodeChrome.overscanPx(display.widthPixels)
        val overscanY = NextEpisodeChrome.overscanPx(display.heightPixels)
        val overlayPad = (12f * display.density).toInt().coerceAtLeast(8)
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(overlayPad, overlayPad, overlayPad, overlayPad)
            setBackgroundColor(0x66000000)
            clipChildren = false
            clipToPadding = false
        }
        titleView = TextView(this).apply {
            text = mediaTitle
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        epgView = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFD0D7DE.toInt())
            setPadding(0, 8, 0, 4)
            isVisible = false
        }
        audioLabelView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF3DDC84.toInt())
            setPadding(0, 4, 0, 8)
            isVisible = false
        }
        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 4, 0, 10)
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            clipChildren = false
        }
        fun controlBtn(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            applyContentSizedButton(this)
            setOnClickListener {
                showOverlayTemporarily()
                action()
            }
        }
        controls.addView(controlBtn("Audio") { showAudioMenu() })
        controls.addView(controlBtn("Subtitles") { showSubtitleMenu() })
        aspectRatioBtn = controlBtn("Aspect") { cycleAspectRatio() }
        controls.addView(aspectRatioBtn)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            controls.addView(controlBtn("PiP") { enterPipMode() })
        }
        controls.addView(controlBtn("Retry") { retryPlayback() })
        controls.addView(controlBtn("Play with VLC") { openInVlc() })
        controls.addView(controlBtn("Favorite") { toggleFavorite() })
        recordButton = controlBtn(if (mediaKind == ContentKind.SERIES) "Record episode" else "Record") {
            val app = application as TotalIptvProApp
            val item = com.totaliptv.pro.data.model.MediaItem(
                id = mediaId,
                name = mediaTitle,
                streamUrl = streamUrl,
                categoryId = null,
                kind = mediaKind
            )
            val thisItem = com.totaliptv.pro.dvr.DvrRecordUi.matches(
                app.dvr.snapshot.value.active,
                mediaId,
                streamUrl
            )
            if (thisItem) {
                com.totaliptv.pro.dvr.DvrActions.stop(this)
                statusView?.text = "Stopping recording…"
            } else {
                com.totaliptv.pro.dvr.DvrActions.recordNow(this, item)
                statusView?.text = if (mediaKind == ContentKind.LIVE) {
                    "Recording on this device…"
                } else {
                    "Downloading on this device…"
                }
            }
            refreshRecordButton()
        }.also { controls.addView(it) }
        refreshRecordButton()
        playNextButton = controlBtn("Play next") {
            val next = cachedNextEpisode
            if (next != null && next.streamUrl.isNotBlank()) {
                playSeriesEpisode(next)
            }
        }.also { btn ->
            btn.isVisible = false
            btn.isFocusable = true
            btn.isFocusableInTouchMode = true
        }
        controls.addView(playNextButton)
        controls.addView(controlBtn("Back") { finish() })
        overlay!!.addView(titleView)
        overlay!!.addView(epgView)
        overlay!!.addView(audioLabelView)
        overlay!!.addView(statusView)
        val controlScroll = HorizontalScrollView(this).apply {
            isFillViewport = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            isFocusable = false
            isFocusableInTouchMode = false
            addView(
                controls,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        overlay!!.addView(
            controlScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        val banner = overlay!!
        banner.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val overlayHost = NextEpisodeOverlayHost(
            this,
            NextEpisodeChrome.overlayMaxHeightPx(display.heightPixels, overscanY)
        ).apply {
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                setMargins(overscanX, overscanY, overscanX, overscanY)
            }
            addView(banner)
        }
        root.addView(overlayHost)
        setContentView(root)
        enterImmersiveFullscreen()

        if (streamUrl.isBlank()) {
            statusView?.text = "Missing stream URL"
            return
        }

        // Always start built-in ExoPlayer. Guide/live catalog URLs are .m3u8;
        // ExoPlayer plays .ts first (progressive, same shape as VOD .mp4).
        // VLC Intent still uses [streamUrl] (.m3u8) as a fallback button.
        playbackUrl = PlayerStream.preferredExoUrl(streamUrl, isLivePlayback())
        triedAlternateLiveUrl = false
        initPlayer()
        scope.launch {
            loadEpgOverlay()
            showOverlayTemporarily()
        }
        scope.launch {
            (application as TotalIptvProApp).dvr.snapshot.collect { refreshRecordButton() }
        }
    }

    private fun refreshRecordButton() {
        val btn = recordButton ?: return
        val app = application as? TotalIptvProApp ?: return
        val idle = if (mediaKind == ContentKind.SERIES) "Record episode" else "Record"
        val look = com.totaliptv.pro.dvr.DvrRecordUi.appearance(
            app.dvr.snapshot.value.active,
            mediaId,
            streamUrl,
            idle
        )
        btn.text = look.label
        if (look.selected) {
            btn.setBackgroundColor(0xFFE53935.toInt())
            btn.setTextColor(Color.WHITE)
            btn.setTypeface(btn.typeface, Typeface.BOLD)
        } else {
            btn.backgroundTintList = null
            btn.setTextColor(0xFFD0D7DE.toInt())
            btn.setTypeface(null, Typeface.NORMAL)
        }
    }

    private fun showOverlayTemporarily(ms: Long = 4500) {
        refreshPlayNextButton()
        refreshRecordButton()
        overlay?.isVisible = true
        hideOverlayJob?.cancel()
        hideOverlayJob = scope.launch {
            delay(ms)
            // Keep overlay if still buffering/error text
            val busy = statusView?.text?.isNotBlank() == true && statusView?.isVisible == true
            if (!busy) overlay?.isVisible = false
        }
    }

    /** Hide title/controls when the ExoPlayer OSD hides (unless buffering/error). */
    private fun hideOverlayIfIdle() {
        hideOverlayJob?.cancel()
        val busy = statusView?.text?.isNotBlank() == true && statusView?.isVisible == true
        if (!busy) {
            overlay?.isVisible = false
            playNextButton?.isVisible = false
        }
    }

    /**
     * Show Play next on the playback OSD whenever a series episode has a following episode.
     * Visibility tracks the OSD/overlay — not only end-of-episode.
     */
    private fun refreshPlayNextButton() {
        val btn = playNextButton ?: return
        val seriesEp = mediaKind == ContentKind.SERIES && mediaId.startsWith("series-ep-")
        if (!seriesEp) {
            cachedNextEpisode = null
            btn.isVisible = false
            return
        }
        scope.launch {
            val next = runCatching {
                (application as TotalIptvProApp).repository.resolveNextSeriesEpisode(
                    catalogId,
                    mediaId
                )
            }.getOrNull()
            if (isFinishing) return@launch
            cachedNextEpisode = next
            val show = next != null && next.streamUrl.isNotBlank() && overlay?.isVisible == true
            btn.isVisible = show
            if (show) {
                val label = next!!.name.substringAfter(" — ").ifBlank { next.name }
                btn.text = "Play next"
                btn.contentDescription = "Play next: $label"
            }
        }
    }

    private fun loadEpgOverlay() {
        if (mediaKind != ContentKind.LIVE) return
        val app = application as TotalIptvProApp
        scope.launch(Dispatchers.IO) {
            val item = app.repository.itemById(mediaId) ?: return@launch
            val nowNext: EpgNowNext = app.repository.epgForChannel(item)
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val text = buildString {
                nowNext.now?.let {
                    append("Now: ${it.title} (${fmt.format(Date(it.startMs))}–${fmt.format(Date(it.endMs))})")
                }
                nowNext.next?.let {
                    if (isNotEmpty()) append("\n")
                    append("Next: ${it.title} (${fmt.format(Date(it.startMs))})")
                }
            }
            if (text.isNotBlank()) {
                launch(Dispatchers.Main) {
                    epgView?.text = text
                    epgView?.isVisible = true
                    showOverlayTemporarily()
                }
            }
        }
    }

    private fun isLivePlayback(): Boolean = mediaKind == ContentKind.LIVE

    /** Build MediaItem; apply live target offset only for LIVE IPTV. */
    private fun buildMediaItem(url: String = playbackUrl.ifBlank { streamUrl }): MediaItem {
        val uri = if (url.startsWith("/") || url.startsWith("\\")) {
            android.net.Uri.fromFile(java.io.File(url))
        } else {
            android.net.Uri.parse(url)
        }
        val builder = MediaItem.Builder().setUri(uri)
        if (!omitForcedMime) {
            PlayerStream.mimeForUrl(url)?.let { builder.setMimeType(it) }
        }
        // Do not force a live target offset. Xtream HLS windows are ~5–12s;
        // a 6–35s target sat behind live and never reached READY (VOD is .mp4).
        return builder.build()
    }

    private fun buildLoadControl(): DefaultLoadControl {
        // LIVE: small cushions so a short window / MPEG-TS can start (VOD-like).
        // The old 30s/120s live buffers never filled on a 5–12s HLS window.
        val minBufferMs: Int
        val maxBufferMs: Int
        val bufferForPlaybackMs: Int
        val bufferForPlaybackAfterRebufferMs: Int
        if (isLivePlayback()) {
            minBufferMs = PlayerStream.LIVE_MIN_BUFFER_MS
            maxBufferMs = PlayerStream.LIVE_MAX_BUFFER_MS
            bufferForPlaybackMs = PlayerStream.LIVE_PLAYBACK_BUFFER_MS
            bufferForPlaybackAfterRebufferMs = PlayerStream.LIVE_REBUFFER_MS
        } else {
            minBufferMs = 15_000
            maxBufferMs = 50_000
            bufferForPlaybackMs = 1_500
            bufferForPlaybackAfterRebufferMs = 3_000
        }
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    private fun buildRenderersFactory(preferSoftware: Boolean): DefaultRenderersFactory {
        // Aggressive decoder fallback: if HW decode fails, ExoPlayer tries the next codec.
        val factory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        if (preferSoftware) {
            // Soft-prefer: list software codecs first so fallback starts with SW when HW is broken
            // (common on Goldfish emulator for HEVC / high-profile streams).
            factory.setMediaCodecSelector(
                MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    MediaCodecSelector.DEFAULT
                        .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                        .sortedByDescending { info -> info.softwareOnly }
                }
            )
        }
        return factory
    }

    private fun friendlyPlaybackError(error: PlaybackException): String {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                "Decode failed (HEVC/high-profile common on emulators). Tap Retry, or Play with VLC."
            else ->
                "Error: ${error.errorCodeName}. Press Retry or Play with VLC."
        }
    }

    /** Resolve installed VLC package (release or debug). */
    private fun findVlcPackage(): String? {
        val pm = packageManager
        for (pkg in VLC_PACKAGES) {
            val installed = runCatching {
                pm.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
            if (installed) return pkg
        }
        return null
    }

    /**
     * Launch current stream URL in VLC via ACTION_VIEW.
     * Does not crash if VLC is missing — shows a clear install toast.
     */
    private fun openInVlc() {
        if (streamUrl.isBlank()) {
            Toast.makeText(this, "No stream URL to open in VLC", Toast.LENGTH_SHORT).show()
            return
        }
        val pkg = findVlcPackage()
        if (pkg == null) {
            Toast.makeText(
                this,
                "Install VLC from Play Store / sideload for Android TV",
                Toast.LENGTH_LONG
            ).show()
            statusView?.text = "VLC not installed. Install from Play Store or sideload for Android TV."
            statusView?.isVisible = true
            overlay?.isVisible = true
            return
        }
        val uri = Uri.parse(streamUrl)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Help cleartext HTTP streams when VLC needs it
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("title", mediaTitle)
        }
        try {
            // Pause built-in so both don't fight for audio.
            // Preferred=VLC often opens before ExoPlayer exists — still bookmark for Home Continue watching.
            saveVlcContinueBookmark()
            player?.playWhenReady = false
            startActivity(intent)
            Toast.makeText(this, "Opened in VLC ($pkg)", Toast.LENGTH_SHORT).show()
            statusView?.text = "Playing in VLC. Use Back to return, or Retry for built-in."
            statusView?.isVisible = true
            overlay?.isVisible = true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "Install VLC from Play Store / sideload for Android TV",
                Toast.LENGTH_LONG
            ).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "Could not open VLC: ${SensitiveText.forUser(t)}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initPlayer() {
        try {
            initPlayerInner()
        } catch (t: Throwable) {
            showPlaybackFailure(t)
        }
    }

    /**
     * Rebuild after the current listener callback returns. Calling [initPlayer] directly from
     * [Player.Listener.onPlayerError] releases the player that is still delivering the event.
     */
    private fun scheduleRebuild(reason: String) {
        statusView?.text = reason
        statusView?.isVisible = true
        overlay?.isVisible = true
        val view = playerView
        if (view == null) {
            initPlayer()
            return
        }
        view.post {
            if (isFinishing || isDestroyed) return@post
            initPlayer()
        }
    }

    private fun handlePlayerError(exo: ExoPlayer, error: PlaybackException) {
        logPlaybackFailure("Player error ${error.errorCodeName}", error)
        if (exo !== player) return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            statusView?.text = "Catching live edge…"
            statusView?.isVisible = true
            runCatching {
                exo.seekToDefaultPosition()
                exo.prepare()
                exo.playWhenReady = true
            }.onFailure { showPlaybackFailure(it) }
            return
        }
        val parsingFail =
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        // Movies are often labeled .mp4 when the file is MKV or HLS. Sniff once before giving up.
        if (parsingFail && !isLivePlayback() && !omitForcedMime) {
            omitForcedMime = true
            scheduleRebuild("This file didn't match its extension. Retrying…")
            return
        }
        val httpFail = parsingFail ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
        if (httpFail && tryAlternateLiveUrl("Retrying live URL…")) {
            return
        }
        val decodeFail =
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        if (decodeFail && !preferSoftwareDecoders) {
            preferSoftwareDecoders = true
            scheduleRebuild("Decoder failed - retrying with software decoder...")
            return
        }
        statusView?.text = friendlyPlaybackError(error)
        statusView?.isVisible = true
        overlay?.isVisible = true
        if (decodeFail) {
            scope.launch {
                val preferred = runCatching {
                    (application as TotalIptvProApp).preferences.getPreferredPlayer()
                }.getOrDefault(PreferredPlayer.BUILTIN)
                if (preferred == PreferredPlayer.ASK && !isFinishing) {
                    runCatching {
                        Toast.makeText(
                            this@PlayerActivity,
                            "Built-in decode failed. Try Play with VLC.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun initPlayerInner() {
        englishAutoApplied.set(false)
        // Detach first. Releasing a player PlayerView still owns crashes inside the view listener.
        playerView?.player = null
        val previous = player
        player = null
        runCatching { previous?.release() }.onFailure {
            logPlaybackFailure("Player release failed", it)
        }

        val renderersFactory = buildRenderersFactory(preferSoftwareDecoders)

        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                // Avoid forced upscaling work when multiple video tracks exist.
                .setViewportSizeToPhysicalDisplaySize(this@PlayerActivity, /* orientationMayChange= */ true)
                .setAllowVideoMixedMimeTypeAdaptiveness(false)
                // Prefer English audio (and English text) whenever the stream offers it.
                .setPreferredAudioLanguage("en")
                .setPreferredTextLanguage("en")
                .build()
        }

        val headers = linkedMapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        )
        PlayerStream.refererFor(playbackUrl.ifBlank { streamUrl })?.let { headers["Referer"] = it }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(PlayerStream.STREAM_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setKeepPostFor302Redirects(true)
            .setDefaultRequestProperties(headers)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val playerBuilder = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(buildLoadControl())

        if (isLivePlayback()) {
            playerBuilder.setLivePlaybackSpeedControl(
                DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMinPlaybackSpeed(0.97f)
                    .setFallbackMaxPlaybackSpeed(1.03f)
                    // Keep speed near 1x while MediaItem LiveConfiguration holds target offset.
                    .setMinUpdateIntervalMs(1_000)
                    .setTargetLiveOffsetIncrementOnRebufferMs(4_000)
                    .build()
            )
        }

        val exo = playerBuilder.build().also { player = it }
        playerView?.apply {
            player = exo
            // FIT avoids stretch/crop rescale artifacts; SurfaceView path stays default.
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        exo.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        // Emulators / soft-rebuild paths sometimes leave volume unset or focus lost.
        exo.volume = 1f
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus= */ true
        )
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Never release or rebuild this player inside the callback. Media3 is still
                // flushing the error event; release() here kills the process instead of
                // showing the message below. Movies hit this more often than live MPEG-TS.
                runCatching { handlePlayerError(exo, error) }.onFailure { showPlaybackFailure(it) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                runCatching {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            statusView?.text = "Buffering..."
                            statusView?.isVisible = true
                            overlay?.isVisible = true
                            watchLiveBufferStuck()
                        }
                        Player.STATE_READY -> {
                            bufferWatchJob?.cancel()
                            statusView?.text = ""
                            statusView?.isVisible = false
                            retryCount = 0
                            runCatching { preferEnglishAudioIfNeeded() }
                                .onFailure { logPlaybackFailure("Audio selection failed", it) }
                            refreshAudioLabel()
                            warnIfNoAudioTracks()
                            runCatching { maybeResumePosition(exo) }
                                .onFailure { showPlaybackFailure(it) }
                            startProgressAutosave()
                            refreshPlayNextButton()
                            showOverlayTemporarily()
                        }
                        Player.STATE_ENDED -> {
                            statusView?.text = "Ended"
                            statusView?.isVisible = true
                            overlay?.isVisible = true
                            clearProgressIfVod()
                            maybeOfferNextEpisode()
                        }
                    }
                }.onFailure { showPlaybackFailure(it) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                runCatching {
                    if (exo.playbackState == Player.STATE_READY) {
                        runCatching { preferEnglishAudioIfNeeded() }
                            .onFailure { logPlaybackFailure("Audio selection failed", it) }
                        refreshAudioLabel()
                        runCatching { maybeResumePosition(exo) }
                            .onFailure { showPlaybackFailure(it) }
                    }
                }.onFailure { showPlaybackFailure(it) }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    runCatching { savePlaybackProgress() }
                        .onFailure { logPlaybackFailure("Could not save progress", it) }
                }
            }
        })
        exo.setMediaItem(buildMediaItem())
        exo.prepare()
        exo.playWhenReady = true
        statusView?.text = if (preferSoftwareDecoders) {
            "Starting (software decoder)…"
        } else {
            "Starting..."
        }
    }

    private fun listAudioOptions(includeUnsupported: Boolean = true): List<AudioTrackOption> {
        val exo = player ?: return emptyList()
        val options = mutableListOf<AudioTrackOption>()
        exo.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO || group.length == 0) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                val supported = group.isTrackSupported(trackIndex)
                // Emulators often mark AC3/E-AC3/DTS as unsupported; still list them so the user
                // can try selecting / see why there is silence, and so we can surface VLC advice.
                if (!supported && !includeUnsupported) continue
                val format = group.getTrackFormat(trackIndex)
                val lang = format.language
                val secondary = isSecondaryAudio(format)
                val isDefault = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
                val label = buildAudioLabel(lang, format.label, trackIndex, format, supported, secondary)
                options += AudioTrackOption(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    group = group.mediaTrackGroup,
                    label = label,
                    language = lang,
                    selected = group.isTrackSelected(trackIndex),
                    isEnglish = isEnglishLanguage(lang) || isEnglishLanguage(format.label),
                    channelCount = format.channelCount,
                    isDefault = isDefault,
                    supported = supported,
                    secondaryAudio = secondary
                )
            }
        }
        return options
    }

    private fun isSecondaryAudio(format: Format): Boolean {
        val roles = format.roleFlags
        if ((roles and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) return true
        if ((roles and C.ROLE_FLAG_COMMENTARY) != 0) return true
        val blob = listOfNotNull(format.label, format.language)
            .joinToString(" ")
            .lowercase(Locale.US)
        return blob.contains("commentary") ||
            blob.contains("description") ||
            blob.contains("audio desc") ||
            blob.contains("narrat") ||
            Regex("""\bad\b""").containsMatchIn(blob)
    }

    private fun buildAudioLabel(
        language: String?,
        name: String?,
        index: Int,
        format: Format,
        supported: Boolean,
        secondary: Boolean
    ): String {
        val langPart = language?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
        val namePart = name?.takeIf { it.isNotBlank() }
        val base = when {
            langPart != null && namePart != null && !namePart.equals(langPart, true) ->
                "$langPart · $namePart"
            langPart != null -> langPart
            namePart != null -> namePart
            else -> "Track ${index + 1}"
        }
        val extras = mutableListOf<String>()
        format.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US)?.let { extras += it }
        when {
            format.channelCount > 0 -> extras += "${format.channelCount}ch"
            format.channelCount == 0 -> extras += "0ch"
        }
        if (secondary) extras += "secondary"
        if (!supported) extras += "unsupported"
        return if (extras.isEmpty()) base else "$base (${extras.joinToString(", ")})"
    }

    private fun isEnglishLanguage(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val n = raw.trim().lowercase(Locale.US).replace('_', '-')
        if (n in ENGLISH_CODES) return true
        if (n.startsWith("en-") || n.startsWith("eng")) return true
        if (n.contains("english")) return true
        return false
    }

    /**
     * Prefer English with real channels / default flag; never leave audio disabled.
     * Falls back to first playable (or any present) track when preferred English is silent
     * or device marks preferred codec unsupported.
     */
    private fun preferEnglishAudioIfNeeded() {
        val options = listAudioOptions(includeUnsupported = true)
        if (options.isEmpty()) return // wait for TRACKS_CHANGED / STATE_READY with real groups

        fun rank(opt: AudioTrackOption): Int {
            var score = 0
            if (opt.supported) score += 40
            if (opt.hasPlayableChannels) score += 20
            // English must beat non-English defaults whenever it is present.
            if (opt.isEnglish) score += 50
            if (opt.isDefault) score += 10
            if (!opt.secondaryAudio) score += 8
            if (opt.language.isNullOrBlank() && opt.isDefault) score += 3
            return score
        }

        // Always prefer English when available (non-commentary, with channels).
        val englishPool = options.filter {
            it.isEnglish && !it.secondaryAudio && it.hasPlayableChannels
        }
        val preferredEnglish = englishPool.filter { it.supported }.maxByOrNull(::rank)
            ?: englishPool.maxByOrNull(::rank)

        val current = options.firstOrNull { it.selected }

        if (preferredEnglish != null) {
            val alreadyEnglish = current != null &&
                current.isEnglish &&
                !current.secondaryAudio &&
                current.hasPlayableChannels &&
                (current.supported || preferredEnglish.let { !it.supported })
            if (alreadyEnglish && englishAutoApplied.get()) {
                ensureAudioOutputEnabled()
                return
            }
            if (alreadyEnglish &&
                current!!.groupIndex == preferredEnglish.groupIndex &&
                current.trackIndex == preferredEnglish.trackIndex
            ) {
                ensureAudioOutputEnabled()
                englishAutoApplied.set(true)
                return
            }
            selectAudio(preferredEnglish, announce = options.size > 1 || !preferredEnglish.supported, auto = true)
            ensureAudioOutputEnabled()
            englishAutoApplied.set(true)
            return
        }

        // No English track — keep prior resilience for silent / unsupported codecs.
        if (englishAutoApplied.get()) {
            val broken = current == null ||
                (!current.hasPlayableChannels && options.any { it.hasPlayableChannels }) ||
                (!current.supported && options.any { it.supported && it.hasPlayableChannels })
            if (!broken) {
                ensureAudioOutputEnabled()
                return
            }
        }

        val playable = options.filter { it.hasPlayableChannels }
        val supportedPlayable = playable.filter { it.supported }
        val pool = when {
            supportedPlayable.isNotEmpty() -> supportedPlayable
            playable.isNotEmpty() -> playable
            else -> options
        }

        val best = pool.maxByOrNull(::rank) ?: options.first()
        val currentOk = current != null &&
            current.hasPlayableChannels &&
            !current.secondaryAudio &&
            (current.supported || pool.none { it.supported })

        if (currentOk) {
            ensureAudioOutputEnabled()
            englishAutoApplied.set(true)
            return
        }

        selectAudio(best, announce = options.size > 1 || !best.supported, auto = true)
        ensureAudioOutputEnabled()
        englishAutoApplied.set(true)
    }

    private fun ensureAudioOutputEnabled() {
        val exo = player ?: return
        exo.volume = 1f
        // Clear any accidental disable of the audio renderer type after soft-decoder rebuild.
        val params = exo.trackSelectionParameters
        if (params.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)) {
            exo.trackSelectionParameters = params
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, /* disabled= */ false)
                .build()
        }
    }

    private fun warnIfNoAudioTracks() {
        val options = listAudioOptions(includeUnsupported = true)
        if (options.isNotEmpty()) {
            // If every listed track is unsupported, surface that clearly while video plays silent.
            if (options.none { it.supported }) {
                val tipBits = options.mapNotNull { opt ->
                    opt.label.substringAfter("(", "").substringBefore(")", "").takeIf { it.isNotBlank() }
                }.distinct().take(3)
                val tip = if (tipBits.isNotEmpty()) " (${tipBits.joinToString(", ")})" else ""
                statusView?.text =
                    "No playable audio$tip — codec may be unsupported on this device/emulator. Try Play with VLC or a real TV."
                statusView?.isVisible = true
                overlay?.isVisible = true
                audioLabelView?.text = "Audio: unsupported on device"
                audioLabelView?.isVisible = true
            }
            return
        }
        statusView?.text =
            "No audio tracks (codec may be unsupported on this device) — try VLC or a real TV"
        statusView?.isVisible = true
        overlay?.isVisible = true
        audioLabelView?.text = "Audio: none found"
        audioLabelView?.isVisible = true
    }

    private fun selectAudio(option: AudioTrackOption, announce: Boolean, auto: Boolean = false) {
        val exo = player ?: return
        ensureAudioOutputEnabled()
        // TrackSelectionOverride throws IndexOutOfBoundsException when the index is outside
        // the group. Movies with several audio tracks hit this from STATE_READY; live often
        // has one track and returns earlier. An uncaught throw here quits the app.
        val applied = runCatching {
            if (option.trackIndex < 0 || option.trackIndex >= option.group.length) {
                error("Audio track ${option.trackIndex} outside group of ${option.group.length}")
            }
            val override = TrackSelectionOverride(option.group, listOf(option.trackIndex))
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, /* disabled= */ false)
                .setOverrideForType(override)
                .build()
            exo.volume = 1f
        }
        if (applied.isFailure) {
            logPlaybackFailure("Audio selection failed", applied.exceptionOrNull())
            statusView?.text = "Couldn't switch audio. Playback continues."
            statusView?.isVisible = true
            overlay?.isVisible = true
            return
        }
        refreshAudioLabel()
        if (announce) {
            val prefix = when {
                auto && !option.supported -> "Audio (may be unsupported)"
                auto -> "Audio (preferred)"
                else -> "Audio"
            }
            Toast.makeText(this, "$prefix: ${option.label}", Toast.LENGTH_SHORT).show()
            audioLabelView?.text = "Audio: ${option.label}"
            audioLabelView?.isVisible = true
            showOverlayTemporarily()
        }
    }

    private fun refreshAudioLabel() {
        val selected = listAudioOptions().firstOrNull { it.selected }
        if (selected != null) {
            audioLabelView?.text = "Audio: ${selected.label}"
            audioLabelView?.isVisible = true
        }
    }

    private fun showAudioMenu() {
        val options = listAudioOptions(includeUnsupported = true)
        if (options.isEmpty()) {
            Toast.makeText(
                this,
                "No audio tracks found (codec may be unsupported on this device/emulator). Try Play with VLC or a real TV.",
                Toast.LENGTH_LONG
            ).show()
            statusView?.text =
                "No audio tracks (codec may be unsupported on this device) — try VLC or a real TV"
            statusView?.isVisible = true
            overlay?.isVisible = true
            return
        }
        val labels = options.map { opt ->
            buildString {
                if (opt.selected) append("✓ ")
                append(opt.label)
                if (opt.isEnglish) append("  (English)")
                if (opt.isDefault) append("  [default]")
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Audio tracks")
            .setItems(labels) { _, which ->
                val opt = options[which]
                selectAudio(opt, announce = true)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Guide/live: if MPEG-TS fails or HLS never leaves BUFFERING, flip to the
     * other Xtream form (`.ts` ↔ `.m3u8`). One flip only.
     */
    private fun tryAlternateLiveUrl(reason: String): Boolean {
        if (!isLivePlayback() || triedAlternateLiveUrl) return false
        val alt = PlayerStream.alternateLiveUrl(playbackUrl.ifBlank { streamUrl }, streamUrl)
            ?: return false
        triedAlternateLiveUrl = true
        playbackUrl = alt
        statusView?.text = reason
        statusView?.isVisible = true
        overlay?.isVisible = true
        Log.i("TotalIPTV.Live", "liveUrlFlip reason=$reason url=${SensitiveText.redact(alt)}")
        scheduleRebuild(reason)
        return true
    }

    private fun watchLiveBufferStuck() {
        if (!isLivePlayback() || triedAlternateLiveUrl) return
        bufferWatchJob?.cancel()
        bufferWatchJob = scope.launch {
            delay(PlayerStream.LIVE_STUCK_BUFFER_MS)
            val exo = player ?: return@launch
            if (exo.playbackState == Player.STATE_BUFFERING && !exo.isPlaying) {
                tryAlternateLiveUrl("Live still buffering — trying other URL…")
            }
        }
    }

    /**
     * Landscape after [onCreate] has returned from the framework. A manifest
     * `screenOrientation` is applied inside `super.onCreate`, which is too early
     * to catch, and Android 8.0 rejects it outright.
     */
    private fun lockLandscapeIfSafe() {
        if (!PlaybackOrientation.allowLandscapeLock(
                android.os.Build.VERSION.SDK_INT,
                isInMultiWindowMode
            )
        ) {
            return
        }
        try {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } catch (t: Throwable) {
            logPlaybackFailure("Could not lock landscape", t)
        }
    }

    /**
     * Hide status and navigation bars and keep the video edge to edge.
     * Touch [android.view.Window.getDecorView] before asking for the insets controller.
     * On API 30, [android.view.Window.getInsetsController] NPEs when DecorView is still null,
     * and the Kotlin safe-call cannot catch that.
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
            logPlaybackFailure("Immersive fullscreen failed", t)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode) {
            enterImmersiveFullscreen()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isInPictureInPictureMode) {
            enterImmersiveFullscreen()
        }
    }

    private fun cycleAspectRatio() {
        val nextMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        currentResizeMode = nextMode
        playerView?.resizeMode = nextMode
        val label = when (nextMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit (Normal)"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch (Fill)"
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom (Crop)"
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "16:9"
            else -> "Fit"
        }
        Toast.makeText(this, "Aspect: $label", Toast.LENGTH_SHORT).show()
        aspectRatioBtn?.text = "Aspect: $label"
    }

    private data class SubtitleTrackOption(
        val groupIndex: Int,
        val trackIndex: Int,
        val group: TrackGroup,
        val label: String,
        val selected: Boolean
    )

    private fun listSubtitleOptions(): List<SubtitleTrackOption> {
        val exo = player ?: return emptyList()
        val options = mutableListOf<SubtitleTrackOption>()
        exo.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_TEXT || group.length == 0) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val lang = format.language?.uppercase(Locale.US)
                val label = format.label ?: lang ?: "Track ${trackIndex + 1}"
                options += SubtitleTrackOption(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    group = group.mediaTrackGroup,
                    label = label,
                    selected = group.isTrackSelected(trackIndex)
                )
            }
        }
        return options
    }

    private fun showSubtitleMenu() {
        val exo = player ?: return
        val options = listSubtitleOptions()
        if (options.isEmpty()) {
            Toast.makeText(this, "No subtitles found in this stream", Toast.LENGTH_SHORT).show()
            return
        }
        val items = mutableListOf("Off (Disable)")
        options.forEach { opt ->
            items.add((if (opt.selected) "✓ " else "") + opt.label)
        }
        AlertDialog.Builder(this)
            .setTitle("Subtitles / Closed Captions")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    Toast.makeText(this, "Subtitles: Off", Toast.LENGTH_SHORT).show()
                } else {
                    val opt = options[which - 1]
                    val override = TrackSelectionOverride(opt.group, listOf(opt.trackIndex))
                    exo.trackSelectionParameters = exo.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(override)
                        .build()
                    Toast.makeText(this, "Subtitles: ${opt.label}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun enterPipMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .build()
            try {
                enterPictureInPictureMode(params)
            } catch (_: Exception) {
                Toast.makeText(this, "PiP not supported on this device", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "PiP requires Android 8.0+", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!autoPipEnabled) return
        if (player?.isPlaying == true && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            overlay?.isVisible = false
            playerView?.useController = false
        } else {
            playerView?.useController = true
            showOverlayTemporarily()
        }
    }

    private fun retryPlayback() {
        retryCount++
        statusView?.text = "Retry #$retryCount…"
        statusView?.isVisible = true
        overlay?.isVisible = true
        englishAutoApplied.set(false)
        omitForcedMime = false
        resumeWaitAttempts = 0
        playbackUrl = PlayerStream.preferredExoUrl(streamUrl, isLivePlayback())
        triedAlternateLiveUrl = false
        bufferWatchJob?.cancel()
        // Rebuild so decoder-fallback / software-preferring factory stays applied.
        initPlayer()
    }

    private fun toggleFavorite() {
        val app = application as TotalIptvProApp
        scope.launch {
            app.repository.toggleFavorite(
                com.totaliptv.pro.data.model.MediaItem(
                    id = mediaId,
                    name = mediaTitle,
                    streamUrl = streamUrl,
                    categoryId = null,
                    kind = mediaKind,
                    logoUrl = mediaLogo
                )
            )
            val on = app.repository.isFavorite(mediaId)
            Toast.makeText(
                this@PlayerActivity,
                if (on) "Added to favorites" else "Removed from favorites",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        savePlaybackProgress()
        player?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
        savePlaybackProgress()
        progressSaveJob?.cancel()
        hideOverlayJob?.cancel()
        bufferWatchJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        playerView?.player = null
        val previous = player
        player = null
        runCatching { previous?.release() }
        super.onDestroy()
    }

    private fun supportsResume(): Boolean =
        mediaKind == ContentKind.VOD || mediaKind == ContentKind.SERIES

    /** Seek to saved position once media is ready (VOD/series only). */
    private fun maybeResumePosition(exo: ExoPlayer) {
        if (!supportsResume() || resumeApplied || resumeInProgress) return
        if (exo !== player) return
        resumeInProgress = true
        try {
            if (startOver) {
                resumeApplied = true
                runCatching {
                    watchProgressStore.clear(mediaId)
                    catalogId?.let { watchProgressStore.clear(it) }
                }
                return
            }
            val saved = runCatching {
                watchProgressStore.get(mediaId)
                    ?: catalogId?.let { watchProgressStore.forCatalogItem(it) }
                    ?: watchProgressStore.forCatalogItem(mediaId)
            }.getOrNull()
            val duration = runCatching { exo.duration }.getOrElse {
                showPlaybackFailure(it)
                resumeApplied = true
                return
            }
            when (val decision = PlaybackResume.decide(startOver = false, saved, duration)) {
                PlaybackResume.Decision.WaitForDuration -> {
                    if (resumeWaitAttempts >= 8) {
                        resumeApplied = true
                        return
                    }
                    resumeWaitAttempts++
                    playerView?.postDelayed({
                        if (!isFinishing && !isDestroyed && player === exo) {
                            maybeResumePosition(exo)
                        }
                    }, 250L)
                }
                PlaybackResume.Decision.Skip -> resumeApplied = true
                PlaybackResume.Decision.StartOver -> resumeApplied = true
                is PlaybackResume.Decision.Seek -> {
                    resumeApplied = true
                    val target = decision.positionMs
                    val seek = Runnable {
                        if (isFinishing || isDestroyed || player !== exo) return@Runnable
                        runCatching { exo.seekTo(target) }
                            .onSuccess {
                                runCatching {
                                    Toast.makeText(this, "Resume", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .onFailure { showPlaybackFailure(it) }
                    }
                    val view = playerView
                    if (view != null) view.post(seek) else seek.run()
                }
            }
        } finally {
            resumeInProgress = false
        }
    }

    private fun showPlaybackFailure(error: Throwable) {
        logPlaybackFailure(SensitiveText.forUser(error), error)
        val msg = SensitiveText.forUser(error)
        statusView?.text = "Playback failed: $msg. Press Retry or Play with VLC."
        statusView?.isVisible = true
        overlay?.isVisible = true
    }

    private fun logPlaybackFailure(message: String, error: Throwable?) {
        Log.e("TotalIPTV.Player", "${SensitiveText.redact(message)}: ${SensitiveText.safeLog(error)}")
        val app = runCatching { applicationContext }.getOrNull() ?: return
        DebugLog.append(app, "TotalIPTV.Player", message, error)
    }

    private fun startProgressAutosave() {
        if (!supportsResume()) return
        progressSaveJob?.cancel()
        progressSaveJob = scope.launch {
            while (true) {
                delay(5_000)
                savePlaybackProgress()
            }
        }
    }

    /**
     * When handing off to external VLC, ExoPlayer may never have started (PreferredPlayer.VLC).
     * Still write a >=15s bookmark so Home "Continue watching" lists the title.
     * Preserves a higher existing position when present.
     */
    private fun saveVlcContinueBookmark() {
        if (!supportsResume()) return
        if (!::watchProgressStore.isInitialized) return
        if (mediaId.isBlank()) return
        val existing = runCatching {
            watchProgressStore.get(mediaId)
                ?: catalogId?.let { watchProgressStore.forCatalogItem(it) }
                ?: watchProgressStore.forCatalogItem(mediaId)
        }.getOrNull()
        val exoPos = player?.currentPosition?.takeIf { it > 0L } ?: 0L
        val exoDur = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val pos = maxOf(
            exoPos,
            existing?.positionMs ?: 0L,
            WatchProgressStore.MIN_SAVE_MS
        )
        val dur = when {
            exoDur > 0L -> exoDur
            (existing?.durationMs ?: 0L) > 0L -> existing!!.durationMs
            else -> 0L
        }
        val resolvedCatalog = catalogId
            ?: existing?.catalogId
            ?: when {
                mediaId.startsWith("vod-") -> mediaId
                mediaId.startsWith("series-") && !mediaId.startsWith("series-ep-") -> mediaId
                else -> null
            }
        if (catalogId.isNullOrBlank() && !resolvedCatalog.isNullOrBlank()) {
            catalogId = resolvedCatalog
        }
        val progress = WatchProgress(
            id = mediaId,
            positionMs = pos,
            durationMs = dur,
            title = mediaTitle,
            kind = mediaKind,
            streamUrl = streamUrl,
            logoUrl = mediaLogo,
            updatedAtMs = System.currentTimeMillis(),
            catalogId = resolvedCatalog
        )
        runCatching { watchProgressStore.save(progress) }
            .onSuccess {
                Log.i(
                    "TotalIPTV.Progress",
                    "vlcBookmark id=$mediaId catalog=$resolvedCatalog pos=$pos dur=$dur"
                )
            }
            .onFailure {
                Log.e("TotalIPTV.Progress", "vlcBookmark failed", it)
            }
    }

    private fun savePlaybackProgress() {
        if (!supportsResume()) return
        if (!::watchProgressStore.isInitialized) return
        val exo = player ?: return
        val pos = runCatching { exo.currentPosition }.getOrElse {
            logPlaybackFailure("Could not read playback position", it)
            return
        }
        if (startOver && pos < WatchProgressStore.MIN_SAVE_MS) return
        var dur = runCatching { exo.duration }.getOrElse {
            logPlaybackFailure("Could not read duration", it)
            return
        }
        if (dur == C.TIME_UNSET || dur < 0L) dur = 0L
        if (pos < WatchProgressStore.MIN_SAVE_MS) return
        val resolvedCatalog = catalogId
            ?: runCatching { watchProgressStore.get(mediaId)?.catalogId }.getOrNull()
            ?: runCatching { watchProgressStore.forCatalogItem(mediaId)?.catalogId }.getOrNull()
        if (catalogId.isNullOrBlank() && !resolvedCatalog.isNullOrBlank()) {
            catalogId = resolvedCatalog
        }
        val progress = WatchProgress(
            id = mediaId,
            positionMs = pos.coerceAtLeast(0L),
            durationMs = dur,
            title = mediaTitle,
            kind = mediaKind,
            streamUrl = streamUrl,
            logoUrl = mediaLogo,
            updatedAtMs = System.currentTimeMillis(),
            catalogId = resolvedCatalog
        )
        runCatching { watchProgressStore.save(progress) }
    }


    private var nextEpisodeDialog: AlertDialog? = null

    /** At end of a series episode, offer Play next before returning home. */
    private fun maybeOfferNextEpisode() {
        if (mediaKind != ContentKind.SERIES) return
        if (!mediaId.startsWith("series-ep-")) return
        if (nextEpisodeDialog?.isShowing == true) return
        scope.launch {
            val next = runCatching {
                (application as TotalIptvProApp).repository.resolveNextSeriesEpisode(
                    catalogId,
                    mediaId
                )
            }.getOrNull()
            if (isFinishing) return@launch
            if (next == null || next.streamUrl.isBlank()) {
                statusView?.text = "Series finished"
                return@launch
            }
            val label = next.name.substringAfter(" — ").ifBlank { next.name }
            nextEpisodeDialog?.dismiss()
            nextEpisodeDialog = buildNextEpisodeDialog(label, next)
            nextEpisodeDialog?.show()
            nextEpisodeDialog?.let { dialog ->
                val dm = resources.displayMetrics
                val overscanX = NextEpisodeChrome.overscanPx(dm.widthPixels)
                val width = (dm.widthPixels - overscanX * 2).coerceAtLeast(1)
                dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
                applyContentSizedButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE))
                applyContentSizedButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
            }
        }
    }

    /**
     * End-of-episode prompt. Title and buttons use a smaller text size and
     * wrap their height. The episode label scrolls inside the safe area
     * instead of being clipped on a short screen or under TV overscan.
     */
    private fun buildNextEpisodeDialog(
        label: String,
        next: com.totaliptv.pro.data.model.MediaItem
    ): AlertDialog {
        val dm = resources.displayMetrics
        val scaled = dm.density * resources.configuration.fontScale.coerceAtLeast(0.5f)
        val overscanY = NextEpisodeChrome.overscanPx(dm.heightPixels)
        val padH = NextEpisodeChrome.horizontalPaddingPx(dm.density)
        val padV = NextEpisodeChrome.verticalPaddingPx(scaled)
        val title = TextView(this).apply {
            text = "Episode finished"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NextEpisodeChrome.DIALOG_TITLE_SP)
            setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = true
            setPadding(padH, padV, padH, padV / 2)
        }
        val message = TextView(this).apply {
            text = "Play next?\n\n$label"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NextEpisodeChrome.DIALOG_MESSAGE_SP)
            includeFontPadding = true
            setPadding(padH, padV, padH, padV)
        }
        val scroll = NextEpisodeMessageScroll(
            this,
            NextEpisodeChrome.messageMaxHeightPx(dm.heightPixels, overscanY, scaled)
        ).apply {
            addView(
                message,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return AlertDialog.Builder(this)
            .setCustomTitle(title)
            .setView(scroll)
            .setPositiveButton("Play next") { _, _ -> playSeriesEpisode(next) }
            .setNegativeButton("Home") { _, _ -> finish() }
            .setOnCancelListener { /* stay on ended screen */ }
            .create()
    }

    /**
     * Button height follows the label. A theme minHeight clips descenders when
     * the font scale is large.
     */
    private fun applyContentSizedButton(btn: Button?) {
        if (btn == null) return
        val dm = resources.displayMetrics
        val scaled = dm.density * resources.configuration.fontScale.coerceAtLeast(0.5f)
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, NextEpisodeChrome.BUTTON_TEXT_SP)
        btn.minHeight = 0
        btn.minimumHeight = 0
        btn.minWidth = 0
        btn.minimumWidth = 0
        btn.maxLines = Int.MAX_VALUE
        btn.includeFontPadding = true
        btn.gravity = Gravity.CENTER
        btn.setPadding(
            NextEpisodeChrome.horizontalPaddingPx(dm.density),
            NextEpisodeChrome.verticalPaddingPx(scaled),
            NextEpisodeChrome.horizontalPaddingPx(dm.density),
            NextEpisodeChrome.verticalPaddingPx(scaled)
        )
        val lp = btn.layoutParams
        if (lp == null) {
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        } else {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            btn.layoutParams = lp
        }
    }

    private fun playSeriesEpisode(item: com.totaliptv.pro.data.model.MediaItem) {
        nextEpisodeDialog?.dismiss()
        nextEpisodeDialog = null
        streamUrl = item.streamUrl
        mediaId = item.id
        mediaTitle = item.name
        mediaKind = ContentKind.SERIES
        mediaLogo = item.logoUrl ?: item.posterUrl ?: mediaLogo
        // Preserve EXTRA_CATALOG_ID (series-###) across next-episode plays
        titleView?.text = mediaTitle
        statusView?.text = "Loading next episode..."
        statusView?.isVisible = true
        overlay?.isVisible = true
        startOver = true
        resumeApplied = false
        resumeWaitAttempts = 0
        omitForcedMime = false
        preferSoftwareDecoders = false
        englishAutoApplied.set(false)
        playbackUrl = PlayerStream.preferredExoUrl(item.streamUrl, live = false)
        triedAlternateLiveUrl = false
        bufferWatchJob?.cancel()
        initPlayer()
        cachedNextEpisode = null
        refreshPlayNextButton()
        showOverlayTemporarily()
    }

    private fun clearProgressIfVod() {
        if (!supportsResume()) return
        if (!::watchProgressStore.isInitialized) return
        runCatching {
            watchProgressStore.clear(mediaId)
            catalogId?.let { watchProgressStore.clear(it) }
        }
    }
}
