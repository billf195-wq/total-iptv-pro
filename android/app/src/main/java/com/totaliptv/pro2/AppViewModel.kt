package com.totaliptv.pro2

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.totaliptv.pro2.data.Catalog
import com.totaliptv.pro2.data.CatalogRepository
import com.totaliptv.pro2.data.ChannelEpg
import com.totaliptv.pro2.data.ContentKind
import com.totaliptv.pro2.data.MediaItem
import com.totaliptv.pro2.data.PreferencesStore
import com.totaliptv.pro2.data.ResumeStore
import com.totaliptv.pro2.data.SavedPrefs
import com.totaliptv.pro2.data.SeriesDetail
import com.totaliptv.pro2.update.AppUpdateManager
import com.totaliptv.pro2.update.UpdatePhase
import com.totaliptv.pro2.update.UpdateUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NavSection { HOME, LIVE, MOVIES, SERIES, GUIDE, SETTINGS }

data class AppUiState(
    val prefs: SavedPrefs = SavedPrefs(),
    val catalog: Catalog? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val statusMessage: String? = null,
    val showOnboarding: Boolean = true,
    val section: NavSection = NavSection.HOME,
    val seriesDetail: SeriesDetail? = null,
    val seriesLoading: Boolean = false,
    val seriesError: String? = null,
    val epgByStreamId: Map<Int, ChannelEpg> = emptyMap(),
    val epgLoadingIds: Set<Int> = emptySet(),
    val resumeEntries: List<ResumeStore.ResumeEntry> = emptyList(),
    val searchQuery: String = "",
    val selectedCategoryId: String? = null,
    val browseSort: String = "AZ",
    val update: UpdateUiState = UpdateUiState()
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefsStore = PreferencesStore(app)
    private val resumeStore = ResumeStore(app)
    private val repo = CatalogRepository()
    private val updater = AppUpdateManager(app)

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        val saved = prefsStore.load()
        val (code, name) = updater.localVersion()
        _state.update {
            it.copy(
                prefs = saved,
                showOnboarding = !saved.onboarded,
                loading = saved.onboarded,
                browseSort = saved.browseSort,
                resumeEntries = resumeStore.load(),
                update = UpdateUiState(
                    localVersionCode = code,
                    localVersionName = name,
                    message = ""
                )
            )
        }
        if (saved.onboarded) loadCatalog(saved)
        checkForAppUpdate()
    }

    fun checkForAppUpdate() {
        viewModelScope.launch {
            val (code, name) = updater.localVersion()
            _state.update {
                it.copy(
                    update = it.update.copy(
                        phase = UpdatePhase.Checking,
                        message = "Checking for update…",
                        localVersionCode = code,
                        localVersionName = name
                    )
                )
            }
            val result = withContext(Dispatchers.IO) {
                updater.check(prefsStore.load().updateShelfUrl)
            }
            _state.update {
                it.copy(
                    update = result,
                    statusMessage = if (
                        result.phase == UpdatePhase.Available && it.statusMessage.isNullOrBlank()
                    ) {
                        result.message + " — open Settings to install on Shield"
                    } else {
                        it.statusMessage
                    }
                )
            }
        }
    }

    fun downloadAppUpdate() {
        val cur = _state.value.update
        val remote = cur.remote
        val base = cur.shelfBaseUrl
        if (remote == null || base.isNullOrBlank()) {
            _state.update {
                it.copy(
                    update = it.update.copy(
                        phase = UpdatePhase.Error,
                        message = "No update metadata — check for update first"
                    )
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    update = it.update.copy(
                        phase = UpdatePhase.Downloading,
                        message = "Downloading ${remote.versionName}…"
                    )
                )
            }
            val result = withContext(Dispatchers.IO) { updater.download(remote, base) }
            _state.update { it.copy(update = result) }
        }
    }

    fun markUpdateInstallPending(message: String) {
        _state.update {
            it.copy(
                update = it.update.copy(
                    phase = UpdatePhase.ReadyToInstall,
                    message = message
                )
            )
        }
    }

    fun setUpdateError(message: String) {
        _state.update {
            it.copy(
                update = it.update.copy(
                    phase = UpdatePhase.Error,
                    message = message
                )
            )
        }
    }

    fun setSection(section: NavSection) {
        val liveFamily = setOf(NavSection.LIVE, NavSection.GUIDE)
        val keepLiveFilters = _state.value.section in liveFamily && section in liveFamily
        _state.update {
            it.copy(
                section = section,
                searchQuery = if (keepLiveFilters) it.searchQuery else "",
                selectedCategoryId = if (keepLiveFilters) it.selectedCategoryId else null,
                seriesDetail = null,
                seriesError = null
            )
        }
    }

    fun setSearch(q: String) = _state.update { it.copy(searchQuery = q) }
    fun setCategory(id: String?) = _state.update { it.copy(selectedCategoryId = id) }

    fun setBrowseSort(sort: String) {
        val p = prefsStore.load().copy(browseSort = sort)
        prefsStore.save(p)
        _state.update { it.copy(browseSort = sort, prefs = p) }
    }

    fun setUpdateShelfUrl(url: String) {
        val p = prefsStore.load().copy(
            updateShelfUrl = com.totaliptv.pro2.update.AppUpdateManager.normalizeShelf(url)
        )
        prefsStore.save(p)
        _state.update { it.copy(prefs = p) }
    }

    fun setPosterColumns(cols: Int) {
        val p = prefsStore.load().copy(posterColumns = cols.coerceIn(5, 6))
        prefsStore.save(p)
        _state.update { it.copy(prefs = p) }
    }

    fun connect(p: SavedPrefs) = loadCatalog(p)

    fun refreshFromSaved() {
        val saved = prefsStore.load()
        _state.update { it.copy(prefs = saved) }
        if (!saved.onboarded) {
            _state.update { it.copy(showOnboarding = true) }
            return
        }
        loadCatalog(saved, fromRefresh = true)
    }

    fun changeSource() {
        prefsStore.clearOnboarding()
        _state.update {
            it.copy(
                catalog = null,
                showOnboarding = true,
                error = null,
                statusMessage = null,
                seriesDetail = null,
                epgByStreamId = emptyMap(),
                prefs = prefsStore.load()
            )
        }
    }

    fun openSeries(item: MediaItem) {
        viewModelScope.launch {
            _state.update { it.copy(seriesLoading = true, seriesError = null, seriesDetail = null) }
            try {
                val saved = prefsStore.load()
                val detail = withContext(Dispatchers.IO) { repo.loadSeriesDetail(saved, item) }
                _state.update { it.copy(seriesDetail = detail, seriesLoading = false) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(seriesError = t.message ?: t.javaClass.simpleName, seriesLoading = false)
                }
            }
        }
    }

    fun closeSeries() = _state.update {
        it.copy(seriesDetail = null, seriesError = null, seriesLoading = false)
    }

    fun recordPlay(item: MediaItem) {
        if (item.kind == ContentKind.VOD || item.kind == ContentKind.SERIES) {
            val entries = resumeStore.recordPlay(item)
            _state.update { it.copy(resumeEntries = entries) }
        }
    }

    fun needEpg(item: MediaItem) {
        val sid = item.xtreamStreamId ?: return
        val cur = _state.value
        if (sid in cur.epgByStreamId || sid in cur.epgLoadingIds) return
        viewModelScope.launch {
            _state.update { it.copy(epgLoadingIds = it.epgLoadingIds + sid) }
            try {
                val saved = prefsStore.load()
                val epg = withContext(Dispatchers.IO) { repo.loadChannelEpg(saved, sid) }
                _state.update { it.copy(epgByStreamId = it.epgByStreamId + (sid to epg)) }
            } catch (_: Throwable) {
                _state.update {
                    it.copy(epgByStreamId = it.epgByStreamId + (sid to ChannelEpg(sid, emptyList())))
                }
            } finally {
                _state.update { it.copy(epgLoadingIds = it.epgLoadingIds - sid) }
            }
        }
    }

    private fun loadCatalog(p: SavedPrefs, fromRefresh: Boolean = false) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = !fromRefresh,
                    refreshing = fromRefresh,
                    error = null,
                    statusMessage = if (fromRefresh) "Updating Live / Movies / Series…" else null
                )
            }
            try {
                val cat = withContext(Dispatchers.IO) { repo.load(p) }
                val merged = prefsStore.load().copy(
                    sourceType = p.sourceType,
                    xtreamBaseUrl = p.xtreamBaseUrl,
                    xtreamUsername = p.xtreamUsername,
                    xtreamPassword = p.xtreamPassword,
                    onboarded = true
                )
                prefsStore.save(merged)
                _state.update {
                    it.copy(
                        prefs = merged,
                        catalog = cat,
                        showOnboarding = false,
                        loading = false,
                        refreshing = false,
                        seriesDetail = null,
                        seriesError = null,
                        epgByStreamId = emptyMap(),
                        epgLoadingIds = emptySet(),
                        statusMessage = if (fromRefresh) {
                            "Catalog updated — Live ${cat.liveItems.size}, Movies ${cat.vodItems.size}, Series ${cat.seriesItems.size}"
                        } else null
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        error = t.message ?: t.javaClass.simpleName,
                        loading = false,
                        refreshing = false,
                        statusMessage = null,
                        showOnboarding = !prefsStore.load().onboarded && it.catalog == null
                    )
                }
            }
        }
    }
}
