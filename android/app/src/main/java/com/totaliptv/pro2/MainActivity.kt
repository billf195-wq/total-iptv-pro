package com.totaliptv.pro2

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.totaliptv.pro2.data.ContentKind
import com.totaliptv.pro2.data.MediaItem
import com.totaliptv.pro2.data.SeriesPlayback
import com.totaliptv.pro2.player.PlayerActivity
import com.totaliptv.pro2.ui.AppRoot
import com.totaliptv.pro2.ui.TipTheme
import com.totaliptv.pro2.update.AppUpdateManager

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val unknownSourcesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // User returned from unknown-sources settings — retry install if APK ready
            val apk = vm.state.value.update.apkFile
            if (apk != null && AppUpdateManager.canInstallPackages(this)) {
                try {
                    AppUpdateManager.installApk(this, apk)
                    vm.markUpdateInstallPending("Install prompt opened")
                } catch (t: Throwable) {
                    vm.setUpdateError("Install failed: ${t.message ?: t.javaClass.simpleName}")
                }
            } else if (apk != null) {
                vm.setUpdateError("Install permission still denied — enable unknown sources for this app")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TipTheme {
                val state by vm.state.collectAsState()
                AppRoot(
                    state = state,
                    onSection = vm::setSection,
                    onConnect = vm::connect,
                    onRefresh = vm::refreshFromSaved,
                    onChangeSource = vm::changeSource,
                    onSearch = vm::setSearch,
                    onCategory = vm::setCategory,
                    onSort = vm::setBrowseSort,
                    onPosterColumns = vm::setPosterColumns,
                    onOpenSeries = vm::openSeries,
                    onCloseSeries = vm::closeSeries,
                    onNeedEpg = vm::needEpg,
                    onPlay = { item -> play(item) },
                    onResumeEntry = { entry ->
                        val item = MediaItem(
                            id = entry.catalogId,
                            name = entry.episodeLabel ?: entry.name,
                            streamUrl = entry.streamUrl,
                            categoryId = null,
                            kind = com.totaliptv.pro2.data.ContentKind.valueOf(entry.kind),
                            posterUrl = entry.posterUrl,
                            xtreamStreamId = entry.xtreamStreamId,
                            playable = entry.streamUrl.isNotBlank(),
                            parentSeriesId = entry.seriesId,
                            parentSeriesName = entry.name,
                            season = entry.season,
                            episodeNum = entry.episodeNum
                        )
                        if (item.playable) play(item)
                        else {
                            val series = state.catalog?.seriesItems?.find {
                                it.xtreamStreamId == entry.seriesId || it.id == entry.catalogId
                            }
                            if (series != null) vm.openSeries(series)
                        }
                    },
                    onCheckAppUpdate = vm::checkForAppUpdate,
                    onDownloadAppUpdate = vm::downloadAppUpdate,
                    onInstallAppUpdate = { installDownloadedUpdate() },
                    onUpdateShelfUrl = vm::setUpdateShelfUrl
                )
            }
        }
    }

    private fun installDownloadedUpdate() {
        val apk = vm.state.value.update.apkFile
        if (apk == null || !apk.exists()) {
            vm.setUpdateError("No APK downloaded — check/download update first")
            return
        }
        if (!AppUpdateManager.canInstallPackages(this)) {
            vm.markUpdateInstallPending("Allow installs from this app, then tap Install update again")
            unknownSourcesLauncher.launch(AppUpdateManager.intentUnknownSources(this))
            return
        }
        try {
            AppUpdateManager.installApk(this, apk)
            vm.markUpdateInstallPending("Install prompt opened")
        } catch (t: Throwable) {
            vm.setUpdateError("Install failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun play(item: MediaItem) {
        if (!item.playable || item.streamUrl.isBlank()) {
            vm.openSeries(item)
            return
        }
        vm.recordPlay(item)
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, item.streamUrl)
                putExtra(PlayerActivity.EXTRA_TITLE, item.name)
                val detail = vm.state.value.seriesDetail
                if (item.kind == ContentKind.SERIES && detail != null &&
                    (item.parentSeriesId == null || item.parentSeriesId == detail.seriesId)
                ) {
                    val sorted = SeriesPlayback.sortedEpisodes(detail.episodes)
                    val start = sorted.indexOfFirst {
                        it.season == item.season && it.episodeNum == item.episodeNum
                    }.let { if (it >= 0) it else 0 }
                    putStringArrayListExtra(
                        PlayerActivity.EXTRA_URLS,
                        ArrayList(sorted.map { it.streamUrl })
                    )
                    putStringArrayListExtra(
                        PlayerActivity.EXTRA_TITLES,
                        ArrayList(sorted.map { ep -> ep.toMediaItem(detail.name, detail.seriesId).name })
                    )
                    putIntegerArrayListExtra(
                        PlayerActivity.EXTRA_SEASONS,
                        ArrayList(sorted.map { it.season })
                    )
                    putIntegerArrayListExtra(
                        PlayerActivity.EXTRA_EP_NUMS,
                        ArrayList(sorted.map { it.episodeNum })
                    )
                    putExtra(PlayerActivity.EXTRA_START_INDEX, start)
                    putExtra(PlayerActivity.EXTRA_SERIES_ID, detail.seriesId)
                    putExtra(PlayerActivity.EXTRA_SERIES_NAME, detail.name)
                }
            }
        )
    }
}
