package com.totaliptv.pro2.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.totaliptv.pro2.data.ContentKind
import com.totaliptv.pro2.data.ResumeStore

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

        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView?.player = exo
            playIndex(exo, index, announce = false)
            exo.playWhenReady = true
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        if (!playNext()) finish()
                    }
                }
            })
        }
    }

    private fun playIndex(exo: ExoPlayer, i: Int, announce: Boolean) {
        if (i !in urls.indices) return
        index = i
        val title = titles.getOrElse(i) { "" }
        exo.setMediaItem(MediaItem.fromUri(urls[i]))
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
