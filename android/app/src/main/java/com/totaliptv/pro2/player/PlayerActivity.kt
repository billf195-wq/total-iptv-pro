package com.totaliptv.pro2.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.totaliptv.pro2.data.ContentKind
import com.totaliptv.pro2.data.ResumeStore

@UnstableApi
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var urls: List<String> = emptyList()
    private var titles: List<String> = emptyList()
    private var seasons: List<Int> = emptyList()
    private var episodeNums: List<Int> = emptyList()
    private var index: Int = 0
    private var seriesId: Int = 0
    private var seriesName: String = ""
    private var playbackUrl: String = ""
    private var triedTsFallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }

        urls = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
        titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        seasons = intent.getIntegerArrayListExtra(EXTRA_SEASONS).orEmpty()
        episodeNums = intent.getIntegerArrayListExtra(EXTRA_EP_NUMS).orEmpty()
        index = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceAtLeast(0)
        seriesId = intent.getIntExtra(EXTRA_SERIES_ID, 0)
        seriesName = intent.getStringExtra(EXTRA_SERIES_NAME).orEmpty()
        if (urls.isEmpty()) {
            urls = listOf(url)
            titles = listOf(title)
        }
        if (index >= urls.size) index = 0

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = true
            controllerShowTimeoutMs = 4000
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            keepScreenOn = true
        }
        setContentView(playerView)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(PlayerStream.STREAM_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setKeepPostFor302Redirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                PlayerStream.LIVE_MIN_BUFFER_MS,
                PlayerStream.LIVE_MAX_BUFFER_MS,
                PlayerStream.LIVE_PLAYBACK_BUFFER_MS,
                PlayerStream.LIVE_REBUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(
                DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMinPlaybackSpeed(0.97f)
                    .setFallbackMaxPlaybackSpeed(1.03f)
                    .build()
            )
            .build()
            .also { exo ->
                playerView?.player = exo
                playIndex(exo, index, announce = false)
                exo.playWhenReady = true
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                            exo.seekToDefaultPosition()
                            exo.prepare()
                            exo.playWhenReady = true
                            return
                        }
                        val httpFail =
                            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                                error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                        val alt = PlayerStream.alternateLiveUrl(playbackUrl, urls.getOrElse(index) { playbackUrl })
                        if (httpFail && !triedTsFallback && !alt.isNullOrBlank()) {
                            triedTsFallback = true
                            playbackUrl = alt
                            exo.setMediaItem(buildMediaItem(alt))
                            exo.prepare()
                            exo.play()
                            return
                        }
                        Toast.makeText(
                            this@PlayerActivity,
                            "Playback error: ${error.errorCodeName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            if (!playNext()) finish()
                        }
                    }
                })
            }
    }

    private fun buildMediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        PlayerStream.mimeForUrl(url)?.let { builder.setMimeType(it) }
        return builder.build()
    }

    private fun playIndex(exo: ExoPlayer, i: Int, announce: Boolean) {
        if (i !in urls.indices) return
        index = i
        val title = titles.getOrElse(i) { "" }
        playbackUrl = PlayerStream.preferredExoUrl(urls[i], live = PlayerStream.isLivePath(urls[i]))
        triedTsFallback = false
        exo.setMediaItem(buildMediaItem(playbackUrl))
        exo.prepare()
        exo.play()
        setTitle(title)
        if (announce && title.isNotBlank()) {
            Toast.makeText(this, title, Toast.LENGTH_SHORT).show()
        }
        recordCurrent()
    }

    private fun playNext(): Boolean {
        val exo = player ?: return false
        if (index + 1 >= urls.size) return false
        playIndex(exo, index + 1, announce = true)
        return true
    }

    private fun playPrevious(): Boolean {
        val exo = player ?: return false
        if (index - 1 < 0) return false
        playIndex(exo, index - 1, announce = true)
        return true
    }

    private fun recordCurrent() {
        if (seriesId <= 0 || index !in urls.indices) return
        val season = seasons.getOrNull(index)
        val epNum = episodeNums.getOrNull(index)
        val title = titles.getOrElse(index) { seriesName }
        ResumeStore(this).recordPlay(
            com.totaliptv.pro2.data.MediaItem(
                id = "ep-${seriesId}-${season ?: 0}-${epNum ?: index}",
                name = title,
                streamUrl = urls[index],
                categoryId = null,
                kind = ContentKind.SERIES,
                playable = true,
                parentSeriesId = seriesId,
                parentSeriesName = seriesName.ifBlank { null },
                season = season,
                episodeNum = epNum
            )
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                finish()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER -> {
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause(); return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (playNext()) return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (playPrevious()) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_URLS = "urls"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_SEASONS = "seasons"
        const val EXTRA_EP_NUMS = "ep_nums"
        const val EXTRA_START_INDEX = "start_index"
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_SERIES_NAME = "series_name"
    }
}
