package com.totaliptv.pro.data.repo

import com.totaliptv.pro.BuildConfig

import android.util.Log
import com.totaliptv.pro.data.GuideBulkCache
import com.totaliptv.pro.data.LiveChannelMapping
import com.totaliptv.pro.data.Ratings
import com.totaliptv.pro.data.SeriesTitles
import com.totaliptv.pro.data.StreamingService
import com.totaliptv.pro.data.StreamingServices
import com.totaliptv.pro.data.CatalogCategories
import com.totaliptv.pro.data.tmdb.ServiceIndex
import com.totaliptv.pro.util.MemoryBudget
import com.totaliptv.pro.data.tmdb.TmdbServiceCache
import com.totaliptv.pro.data.tmdb.ServiceLookupDeferral
import com.totaliptv.pro.data.tmdb.TmdbServiceLookup
import com.totaliptv.pro.data.tmdb.TmdbPlotLookup
import com.totaliptv.pro.data.VodDiskCache
import com.totaliptv.pro.data.xtream.JsonArrayStream
import com.totaliptv.pro.data.LiveEpgBinding
import com.totaliptv.pro.data.local.AppPreferences
import com.totaliptv.pro.data.m3u.M3uParser
import com.totaliptv.pro.data.m3u.XmltvParser
import com.totaliptv.pro.data.lock.CatalogLockFilter
import com.totaliptv.pro.data.lock.ParentalGate
import com.totaliptv.pro.data.lock.AdultSearchFilter
import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.EpgChannelRow
import com.totaliptv.pro.data.model.EpgNowNext
import com.totaliptv.pro.data.model.EpgProgram
import com.totaliptv.pro.ui.epg.EpgRequestGate
import com.totaliptv.pro.ui.epg.GuideEpgLoad
import com.totaliptv.pro.ui.epg.GuideWindow
import com.totaliptv.pro.data.model.FavoriteRef
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.model.PlaylistSource
import com.totaliptv.pro.data.model.SourceType
import com.totaliptv.pro.data.xtream.XtreamApi
import com.totaliptv.pro.util.SensitiveText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CatalogRepository(
    private val prefs: AppPreferences,
    private val xtreamApi: XtreamApi = XtreamApi(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {
    /** Lists the screens see: the full catalog, minus PIN-locked categories while the lock is active. */
    @Volatile
    private var cachedCategories: List<Category> = emptyList()

    @Volatile
    private var cachedItems: List<MediaItem> = emptyList()

    /** Full catalog as loaded (never filtered). Loads and merges always read and write these. */
    @Volatile
    private var rawCategories: List<Category> = emptyList()

    @Volatile
    private var rawItems: List<MediaItem> = emptyList()

    /** Movies / series merges finished for the current source (for the PIN lock's remembered ids). */
    @Volatile
    private var vodSettled = false

    @Volatile
    private var seriesSettled = false

    @Volatile
    private var catalogIsM3u = false

    private val lockListener: () -> Unit = { repoScope.launch { loadMutex.withLock { publishVisible() } } }

    @Volatile
    private var loadedForSourceId: String? = null

    @Volatile
    var lastWarning: String? = null
        private set

    @Volatile
    private var activeXtreamCreds: XtreamApi.Credentials? = null

    private val loadMutex = Mutex()
    private val posterMutex = Mutex()
    private val posterCache = mutableMapOf<Int, String?>()
    /** Per-stream short/simple EPG cache so category switches reuse already-fetched programs. */
    private val epgCache = ConcurrentHashMap<Int, List<EpgProgram>>()
    /**
     * M3U XMLTV listings keyed by the channel id string (tvg-id / display name).
     * Never store these under a hash of epg_channel_id — that mixed unrelated channels.
     */
    private val xmltvByChannel = ConcurrentHashMap<String, List<EpgProgram>>()
    @Volatile
    private var xmltvUrl: String? = null
    @Volatile
    private var xmltvLoaded: Boolean = false
    private val xmltvLock = Any()
    /** When each stream's listings were stored. Reopening the guide reuses them. */
    private val epgCachedAt = ConcurrentHashMap<Int, Long>()
    private val epgGate = EpgRequestGate(GuideEpgLoad.PARALLEL)
    /** Stream ids present in the xmltv download. Not trimmed with the program map. */
    private val bulkCovered = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val epgInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    @Volatile
    private var bulkGuideReady = false
    /** Short EPG stays off until the cached xmltv pass has finished. */
    @Volatile
    private var shortEpgAllowed = false
    @Volatile
    private var guideCacheDir: File? = null

    private val repoJob = SupervisorJob()
    private val repoScope = CoroutineScope(repoJob + Dispatchers.IO)
    private var vodJob: Job? = null

    @Volatile
    private var showAdultInSearchCached = false

    init {
        ParentalGate.addListener(lockListener)
        repoScope.launch {
            prefs.showAdultInSearch.collect { showAdultInSearchCached = it }
        }
    }

    /** Bumps whenever the in-memory catalog changes (live publish or VOD merge). */
    private val _catalogRevision = MutableStateFlow(0)
    val catalogRevision: StateFlow<Int> = _catalogRevision.asStateFlow()

    /** True while Xtream VOD is still downloading/merging in the background. */
    private val _vodLoading = MutableStateFlow(false)
    val vodLoading: StateFlow<Boolean> = _vodLoading.asStateFlow()

    /** User-facing VOD failure (null = ok). Set only when movies are empty after retries. */
    private val _vodError = MutableStateFlow<String?>(null)
    val vodError: StateFlow<String?> = _vodError.asStateFlow()

    @Volatile
    private var vodCacheDir: File? = null
    @Volatile
    private var vodRetryArgs: List<String>? = null

    /** Where the last good movie/series list is kept (app filesDir). */
    fun attachVodCacheDir(dir: File) {
        vodCacheDir = dir
    }

    @Volatile
    private var vodCacheMigrated = false

    /** 1.4.115: split the 1.4.113/1.4.114 combined cache into per-kind files once (IO thread). */
    private fun ensureVodCacheMigrated() {
        if (vodCacheMigrated) return
        val dir = vodCacheDir ?: return
        synchronized(this) {
            if (vodCacheMigrated) return
            vodCacheMigrated = true
            runCatching { VodDiskCache.migrateLegacy(dir) }
                .onSuccess { Log.i(VOD_TAG, "cache migrate ok=$it") }
                .onFailure { Log.w(VOD_TAG, "cache migrate failed: ${SensitiveText.safeLog(it)}") }
        }
    }

    @Volatile
    private var vodJobStartedMs = 0L

    /** Bumped by every movie/series job; an older job that is still downloading stops and never applies. */
    @Volatile
    private var vodGeneration = 0L

    /** Kinds whose in-memory list changed after its last disk commit (e.g. a resolved trailer). */
    private val dirtyKinds = java.util.Collections.synchronizedSet(HashSet<ContentKind>())

    @Volatile
    private var memoryTight: Boolean = false

    fun attachMemoryBudget(tight: Boolean) {
        memoryTight = tight
    }

    /** Re-run the movie/series download for the active Xtream source. */
    fun retryVod() {
        val args = vodRetryArgs ?: run {
            repoScope.launch { runCatching { ensureCatalogLoaded(force = true) } }
            return
        }
        startVodJob(args[0], args[1], args[2], args[3])
    }

    private fun startVodJob(sourceId: String, base: String, user: String, pass: String) {
        val args = listOf(sourceId, base, user, pass)
        val running = vodJob
        if (running?.isActive == true && vodRetryArgs == args &&
            System.currentTimeMillis() - vodJobStartedMs < VOD_KEEP_RUNNING_MS
        ) {
            // 1.4.115: an Update while the same lists are still downloading keeps that download
            // (it is already fresh) instead of throwing it away and starting over. In 1.4.114
            // each Update restarted movies and then series from zero.
            Log.i(VOD_TAG, "load already running gen=$vodGeneration; keeping it")
            return
        }
        vodRetryArgs = args
        vodJob?.cancel()
        val gen = ++vodGeneration
        _vodLoading.value = true
        _vodError.value = null
        val t0 = System.currentTimeMillis()
        vodJobStartedMs = t0
        vodJob = repoScope.launch {
            try {
                awaitPlaybackIdle(VOD_TAG)
                if (memoryTight) {
                    // Weak boxes: one list at a time keeps the memory peak low.
                    mergeKindInBackground(ContentKind.VOD, gen, sourceId, base, user, pass)
                    awaitPlaybackIdle(VOD_TAG)
                    mergeKindInBackground(ContentKind.SERIES, gen, sourceId, base, user, pass)
                } else {
                    // 1.4.115: series no longer wait for the whole movie list (minutes on big providers).
                    val series = async { mergeKindInBackground(ContentKind.SERIES, gen, sourceId, base, user, pass) }
                    mergeKindInBackground(ContentKind.VOD, gen, sourceId, base, user, pass)
                    series.await()
                }
                Log.i(VOD_TAG, "movies+series done gen=$gen ms=${System.currentTimeMillis() - t0} tight=$memoryTight")
            } finally {
                if (gen == vodGeneration) {
                    _vodLoading.value = false
                    if (com.totaliptv.pro.data.PlaybackGate.refreshAllowed()) {
                        persistVodCache(sourceId)
                    } else {
                        catalogWorkDeferredForPlayback.set(true)
                    }
                }
            }
        }
    }

    /**
     * 1.4.115: the streamed loads commit each kind to disk themselves. This only re-saves a kind
     * whose in-memory list changed afterwards, and never a kind that has no titles in memory,
     * so it can never overwrite a good series (or movie) file with an empty one.
     */
    private fun persistVodCache(sourceId: String) {
        val dir = vodCacheDir ?: return
        if (loadedForSourceId != sourceId) return
        val kinds = synchronized(dirtyKinds) { dirtyKinds.toList().also { dirtyKinds.clear() } }
        if (kinds.isEmpty()) return
        val cats = rawCategories
        val items = rawItems
        for (kind in kinds) {
            if (kind == ContentKind.LIVE) continue
            runCatching { VodDiskCache.writeKind(dir, sourceId, kind, cats, items) }
                .onSuccess { Log.i(VOD_TAG, "cache saved kind=$kind written=$it") }
                .onFailure { Log.w(VOD_TAG, "cache save failed kind=$kind: ${SensitiveText.safeLog(it)}") }
        }
    }

    val sources: Flow<List<PlaylistSource>> = prefs.sources
    /** Saved favorites, minus PIN-locked titles while the lock is active. */
    val favorites: Flow<List<FavoriteRef>> = combine(prefs.favorites, ParentalGate.revision) { list, _ ->
        if (ParentalGate.isActive) list.filterNot { ParentalGate.isHiddenId(it.id) } else list
    }
    val activeSourceId: Flow<String?> = prefs.activeSourceId

    suspend fun addM3uSource(name: String, url: String): PlaylistSource {
        val source = PlaylistSource(
            id = "m3u-" + System.currentTimeMillis(),
            name = name.ifBlank { "M3U Playlist" },
            type = SourceType.M3U,
            m3uUrl = url.trim()
        )
        prefs.addSource(source)
        loadedForSourceId = null
        activeXtreamCreds = null
        return source
    }

    suspend fun addXtreamSource(
        name: String,
        baseUrl: String,
        username: String,
        password: String
    ): PlaylistSource {
        val source = PlaylistSource(
            id = "xtream-" + System.currentTimeMillis(),
            name = name.ifBlank { "Xtream" },
            type = SourceType.XTREAM,
            xtreamBaseUrl = baseUrl.trim(),
            xtreamUsername = username.trim(),
            xtreamPassword = password
        )
        prefs.addSource(source)
        loadedForSourceId = null
        return source
    }

    suspend fun removeSource(id: String) {
        prefs.removeSource(id)
        if (loadedForSourceId == id) {
            rawCategories = emptyList()
            rawItems = emptyList()
            cachedCategories = emptyList()
            cachedItems = emptyList()
            refreshDerivedCatalog()
            loadedForSourceId = null
            activeXtreamCreds = null
            forgetEpg()
            clearXmltv()
            bumpRevision()
        }
    }

    suspend fun clearAllData() {
        vodCacheDir?.let { runCatching { VodDiskCache.clear(it) } }
        _vodError.value = null
        vodRetryArgs = null
        vodGeneration++
        vodJob?.cancel()
        vodJob = null
        _vodLoading.value = false
        prefs.clearAll()
        rawCategories = emptyList()
        rawItems = emptyList()
        cachedCategories = emptyList()
        cachedItems = emptyList()
        refreshDerivedCatalog()
        loadedForSourceId = null
        activeXtreamCreds = null
        lastWarning = null
        posterCache.clear()
        forgetEpg()
        clearXmltv()
        bumpRevision()
    }

    /**
     * Loads the active playlist with retries for transient failures.
     * Xtream: publishes LIVE as soon as it is usable, then loads VOD in the
     * background so a corrupt/huge get_vod_streams body cannot block home
     * or turn a live-only success into a hard error.
     */
    suspend fun ensureCatalogLoaded(force: Boolean = false): Pair<List<Category>, List<MediaItem>> {
        if (!force) return ensureCatalogLoadedInner(false)
        // Manual reload: the app shows the startup logo splash until this returns.
        ManualRefresh.begin()
        try {
            return ensureCatalogLoadedInner(true)
        } finally {
            ManualRefresh.end()
        }
    }

    private suspend fun ensureCatalogLoadedInner(force: Boolean): Pair<List<Category>, List<MediaItem>> {
        var scheduleVod: (() -> Unit)? = null

        val result = loadMutex.withLock {
            withContext(Dispatchers.IO) {
                val activeId = prefs.getActiveSourceId()
                val source = prefs.getSources().firstOrNull { it.id == activeId }
                    ?: prefs.getSources().firstOrNull()
                    ?: return@withContext emptyList<Category>() to emptyList()

                if (!force && loadedForSourceId == source.id && rawItems.isNotEmpty()) {
                    return@withContext cachedCategories to cachedItems
                }

                val hadCache = loadedForSourceId == source.id && rawItems.isNotEmpty()
                var lastError: Throwable? = null
                val maxAttempts = 3

                for (attempt in 1..maxAttempts) {
                    try {
                        when (source.type) {
                            SourceType.M3U -> {
                                vodGeneration++
                                vodJob?.cancel()
                                vodJob = null
                                _vodLoading.value = false
                                activeXtreamCreds = null
                                val url = source.m3uUrl ?: error("Missing M3U URL")
                                val parsed = downloadM3u(url)
                                if (parsed.items.isEmpty()) error("Playlist contained no channels")
                                rawCategories = parsed.categories
                                rawItems = parsed.items
                                catalogIsM3u = true
                                publishVisible(refresh = false)
                                refreshDerivedCatalog()
                                clearXmltv()
                                xmltvUrl = parsed.xmltvUrl
                                if (!parsed.xmltvUrl.isNullOrBlank()) {
                                    repoScope.launch { ensureXmltvLoaded() }
                                }
                                lastWarning = null
                                loadedForSourceId = source.id
                                bumpRevision()
                            }
                            SourceType.XTREAM -> {
                                val base = source.xtreamBaseUrl ?: error("Missing Xtream URL")
                                val user = source.xtreamUsername ?: error("Missing username")
                                val pass = source.xtreamPassword ?: error("Missing password")
                                val live = xtreamApi.loadLiveCatalog(base, user, pass)
                                activeXtreamCreds = XtreamApi.Credentials(base, user, pass)
                                forgetEpg()
                                clearXmltv()
                                // Publish live immediately — do not wait on VOD.
                                // 1.4.91: keep the previous movie/series lists (memory, else disk
                                // cache) until a fresh non-empty load replaces them.
                                val sameSource = loadedForSourceId == source.id
                                // 1.4.115: decide per kind. A kind missing from memory is read from
                                // its own last-good file (or the 1.4.113/1.4.114 combined file).
                                ensureVodCacheMigrated()
                                val keepCats = ArrayList<com.totaliptv.pro.data.model.Category>()
                                val keepItems = ArrayList<MediaItem>()
                                for (kind in VodDiskCache.VOD_KINDS) {
                                    val memItems = if (sameSource) rawItems.filter { it.kind == kind } else emptyList()
                                    if (memItems.isNotEmpty()) {
                                        keepItems.addAll(memItems)
                                        keepCats.addAll(rawCategories.filter { it.kind == kind })
                                        continue
                                    }
                                    val cats = ArrayList<com.totaliptv.pro.data.model.Category>()
                                    val items = ArrayList<MediaItem>()
                                    val meta = vodCacheDir?.let { dir ->
                                        runCatching {
                                            VodDiskCache.readKind(dir, source.id, kind, { cats.addAll(it) }, { items.addAll(it) })
                                        }.getOrNull()
                                    }
                                    if (meta != null && items.isNotEmpty()) {
                                        keepCats.addAll(cats)
                                        keepItems.addAll(items)
                                        Log.i(VOD_TAG, "disk cache hit kind=$kind items=${items.size} cats=${cats.size} ageMs=${System.currentTimeMillis() - meta.savedAtMs}")
                                    } else {
                                        Log.i(VOD_TAG, "no cache yet kind=$kind")
                                    }
                                }
                                rawCategories = live.categories + keepCats
                                rawItems = live.items + keepItems
                                catalogIsM3u = false
                                vodSettled = false
                                seriesSettled = false
                                publishVisible(refresh = false)
                                refreshDerivedCatalog()
                                lastWarning = null
                                loadedForSourceId = source.id
                                bumpRevision()

                                val sourceId = source.id
                                scheduleVod = { startVodJob(sourceId, base, user, pass) }
                            }
                        }
                        return@withContext cachedCategories to cachedItems
                    } catch (t: Throwable) {
                        lastError = t
                        val permanent = isPermanentFailure(t)
                        val canRetry = attempt < maxAttempts && !permanent
                        if (canRetry) {
                            delay(800L * attempt)
                            continue
                        }
                        if (hadCache) {
                            val detail = SensitiveText.forUser(t)
                            lastWarning =
                                "Refresh failed (" + detail + "); showing cached catalog."
                            bumpRevision()
                            return@withContext cachedCategories to cachedItems
                        }
                        throw t
                    }
                }
                throw lastError ?: error("Failed to load catalog")
            }
        }

        scheduleVod?.invoke()
        return result
    }

    /** Runs [load] up to [VOD_ATTEMPTS] times; an empty or failed result counts as a miss. */
    private suspend fun <T> loadWithRetry(
        label: String,
        load: () -> T,
        itemCount: (T) -> Int,
        warnings: (T) -> List<String>
    ): Pair<T?, String?> {
        var lastProblem: String? = null
        for (attempt in 1..VOD_ATTEMPTS) {
            val t0 = System.currentTimeMillis()
            val res = runCatching { load() }
            val ms = System.currentTimeMillis() - t0
            val value = res.getOrNull()
            val n = value?.let(itemCount) ?: 0
            if (value != null && n > 0) {
                Log.i(VOD_TAG, "$label ok attempt=$attempt items=$n ms=$ms")
                return value to null
            }
            lastProblem = res.exceptionOrNull()?.let { SensitiveText.forUser(it) }
                ?: value?.let(warnings)?.joinToString(" ")?.takeIf { it.isNotBlank() }
                ?: "empty list"
            Log.w(VOD_TAG, "$label miss attempt=$attempt/$VOD_ATTEMPTS ms=$ms problem=$lastProblem")
            if (attempt < VOD_ATTEMPTS) delay(VOD_BACKOFF_MS * (1L shl (attempt - 1)))
        }
        return null to lastProblem
    }

    private fun streamBatchSize(): Int =
        if (memoryTight) 400 else JsonArrayStream.DEFAULT_BATCH

    /**
     * 1.4.115: one streamed load for [kind] (movies or series).
     *
     * Rules (the 1.4.114 Series bug broke all of them):
     *  - The last good [kind] list stays in memory and on screen for the whole download;
     *    nothing is dropped up front.
     *  - Each kind is committed to its own file, and only when the new list has at least one
     *    title of that kind. Series no longer depend on a movie being in the same file.
     *  - Each writer has its own temp file; a refresh that starts while an older job is still
     *    downloading makes the older job stop at its next batch and never apply or commit.
     *  - On any failure, last good is kept (memory, else disk) with its categories.
     */
    private suspend fun mergeKindInBackground(
        kind: ContentKind,
        gen: Long,
        sourceId: String,
        base: String,
        user: String,
        pass: String
    ) {
        val label = if (kind == ContentKind.SERIES) "series" else "movies"
        Log.i(VOD_TAG, "$label load start gen=$gen")
        var lastProblem: String? = null
        var loaded = 0
        var loadedCats: List<com.totaliptv.pro.data.model.Category> = emptyList()
        var fallbackKeep: List<MediaItem> = emptyList()
        for (attempt in 1..VOD_ATTEMPTS) {
            if (gen != vodGeneration) return
            val t0 = System.currentTimeMillis()
            var cats: List<com.totaliptv.pro.data.model.Category> = emptyList()
            var count = 0
            var writer: VodDiskCache.Writer? = null
            val fallback = ArrayList<MediaItem>()
            val dir = vodCacheDir
            val onCats: (List<com.totaliptv.pro.data.model.Category>) -> Unit = { cats = it }
            val onBatch: (List<MediaItem>) -> Unit = onBatch@{ batch ->
                if (gen != vodGeneration) throw CancellationException("superseded $label load")
                if (batch.isEmpty()) return@onBatch
                if (writer == null && dir != null) {
                    writer = VodDiskCache.Writer(dir, sourceId, kind, cats)
                }
                writer?.add(batch)
                if (dir == null) fallback.addAll(batch)
                count += batch.count { it.kind == kind }
            }
            val result = runCatching {
                if (kind == ContentKind.SERIES) {
                    xtreamApi.consumeSeriesCatalog(base, user, pass, streamBatchSize(), onCats, onBatch)
                } else {
                    xtreamApi.consumeVodCatalog(base, user, pass, streamBatchSize(), onCats, onBatch)
                }
            }
            val ms = System.currentTimeMillis() - t0
            val ex = result.exceptionOrNull()
            if (ex is CancellationException || gen != vodGeneration) {
                writer?.abort()
                Log.i(VOD_TAG, "$label load superseded gen=$gen; keeping last good")
                if (ex is CancellationException && gen == vodGeneration) throw ex
                return
            }
            val committed = if (result.isSuccess && count > 0) {
                val w = writer
                if (w != null) w.commit() else true
            } else {
                writer?.abort()
                false
            }
            if (committed) {
                Log.i(VOD_TAG, "$label ok attempt=$attempt items=$count cats=${cats.size} ms=$ms committed=$committed")
                loaded = count
                loadedCats = cats
                fallbackKeep = fallback
                lastProblem = null
                break
            }
            lastProblem = ex?.let { SensitiveText.forUser(it) }
                ?: result.getOrNull()?.warnings?.joinToString(" ")?.takeIf { it.isNotBlank() }
                ?: "empty list"
            Log.w(VOD_TAG, "$label miss attempt=$attempt/$VOD_ATTEMPTS items=$count ms=$ms problem=$lastProblem")
            if (attempt < VOD_ATTEMPTS) delay(VOD_BACKOFF_MS * (1L shl (attempt - 1)))
        }

        loadMutex.withLock {
            if (gen != vodGeneration || loadedForSourceId != sourceId) return@withLock
            if (loaded > 0) {
                applyStreamedKind(sourceId, kind, loadedCats, fallbackKeep)
                dirtyKinds.remove(kind)
                if (kind == ContentKind.SERIES) seriesSettled = true else vodSettled = true
                publishVisible(refresh = false)
                refreshDerivedCatalog()
                if (kind == ContentKind.VOD) {
                    lastWarning = null
                    _vodError.value = null
                }
            } else {
                restoreKindFromCache(sourceId, kind)
                if (kind == ContentKind.SERIES) seriesSettled = true else vodSettled = true
                publishVisible(refresh = false)
                refreshDerivedCatalog()
                val have = rawItems.count { it.kind == kind }
                Log.w(VOD_TAG, "$label refresh failed; keeping $have cached $label problem=$lastProblem")
                if (kind == ContentKind.VOD) {
                    if (have == 0) {
                        _vodError.value = "Couldn't load movies (" + (lastProblem ?: "unknown error") + ")."
                        lastWarning = "VOD list failed (" + (lastProblem ?: "unknown error") + "). Live TV still available."
                    } else {
                        lastWarning = "Couldn't refresh movies; showing saved list."
                    }
                } else if (have == 0) {
                    lastWarning = listOfNotNull(lastWarning, "Series list failed (" + (lastProblem ?: "unknown error") + ").").joinToString(" ")
                }
            }
            bumpRevision()
        }
    }

    /** After a failed refresh, put [kind] (titles and categories) back from disk if memory has none. */
    private fun restoreKindFromCache(sourceId: String, kind: ContentKind) {
        if (rawItems.any { it.kind == kind }) return
        val dir = vodCacheDir ?: return
        val cats = ArrayList<com.totaliptv.pro.data.model.Category>()
        val add = ArrayList<MediaItem>()
        runCatching { VodDiskCache.readKind(dir, sourceId, kind, { cats.addAll(it) }, { add.addAll(it) }) }
        if (add.isEmpty()) return
        rawItems = rawItems + add
        if (rawCategories.none { it.kind == kind }) rawCategories = rawCategories + cats
        Log.i(VOD_TAG, "restored kind=$kind items=${add.size} cats=${cats.size} from disk")
    }

    /**
     * Drop the previous list of [kind] and stream the new titles from the file just committed.
     * Live and the other kind stay in memory untouched. When no cache dir is attached (tests),
     * [fallback] is applied instead.
     */
    private fun applyStreamedKind(
        sourceId: String,
        kind: ContentKind,
        newCats: List<com.totaliptv.pro.data.model.Category>,
        fallback: List<MediaItem> = emptyList()
    ) {
        val oldCats = rawCategories.filter { it.kind == kind }
        val cats = newCats.filter { it.kind == kind }.ifEmpty { oldCats }
        val kept = ArrayList<MediaItem>(rawItems.size)
        for (item in rawItems) {
            if (item.kind != kind) kept.add(item)
        }
        // Release the old [kind] graph before the new one is read, so both are never held.
        rawItems = kept
        cachedItems = ArrayList(kept)
        val dir = vodCacheDir
        var added = 0
        if (dir != null) {
            val diskCats = ArrayList<com.totaliptv.pro.data.model.Category>()
            runCatching {
                VodDiskCache.readKind(dir, sourceId, kind, { diskCats.addAll(it) }) { batch ->
                    kept.addAll(batch)
                    added += batch.size
                }
            }.onFailure { Log.w(VOD_TAG, "apply read failed kind=$kind: ${SensitiveText.safeLog(it)}") }
            rawCategories = rawCategories.filter { it.kind != kind } + cats.ifEmpty { diskCats }
        } else {
            kept.addAll(fallback)
            added = fallback.size
            rawCategories = rawCategories.filter { it.kind != kind } + cats
        }
        cachedCategories = rawCategories
        Log.i(VOD_TAG, "applied kind=$kind items=$added cats=${rawCategories.count { it.kind == kind }}")
    }

    /**
     * Rebuild the visible lists from the raw catalog with the PIN lock applied. With the lock off
     * or unlocked the raw lists are published as-is (same instances), so nothing else changes.
     */
    private fun publishVisible(refresh: Boolean = true) {
        val cats = rawCategories
        val items = rawItems
        val complete = catalogIsM3u || (vodSettled && seriesSettled)
        if (ParentalGate.isActive) {
            val r = CatalogLockFilter.apply(cats, items, ParentalGate.config)
            cachedCategories = r.categories
            cachedItems = r.items
            ParentalGate.onCatalogFiltered(r.hiddenItemIds, complete && items.isNotEmpty(), lockedCountOrUnknown(r, items))
        } else {
            cachedCategories = cats
            cachedItems = items
            if (ParentalGate.isEnabled) {
                // Unlocked: keep the remembered ids current without hiding anything.
                val r = CatalogLockFilter.apply(cats, items, ParentalGate.config)
                ParentalGate.onCatalogFiltered(r.hiddenItemIds, complete && items.isNotEmpty(), lockedCountOrUnknown(r, items))
            } else {
                ParentalGate.onCatalogFiltered(emptySet(), false, 0)
            }
        }
        if (refresh) {
            refreshDerivedCatalog()
            bumpRevision()
        }
    }

    /** 1.4.108: locked category count for the PIN status line; unknown (-1) before any list loads. */
    private fun lockedCountOrUnknown(r: CatalogLockFilter.Result, items: List<MediaItem>): Int =
        if (items.isEmpty() && rawCategories.isEmpty()) -1 else r.lockedCategoryIds.size

    /** Every provider category (locked ones included) for the PIN lock picker. */
    fun allCategoriesForLock(kind: ContentKind): List<Category> = rawCategories.filter { it.kind == kind }

    private fun bumpRevision() {
        _catalogRevision.value = _catalogRevision.value + 1
    }

    private class ServiceGroupSnapshot(
        val items: List<MediaItem>,
        val categories: List<Category>,
        val index: ServiceIndex?,
        val groups: Map<Pair<StreamingService, ContentKind>, List<MediaItem>>
    )

    @Volatile
    private var serviceGroupSnapshot: ServiceGroupSnapshot? = null
    private val serviceComputeRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** TMDB-derived title -> services lookup (null until a cache / fetch is available). */
    @Volatile
    private var serviceIndex: ServiceIndex? = null

    /**
     * Streaming service virtual groups. Built off the main thread
     * (55k-title catalogs); returns the last finished result immediately and bumps
     * [catalogRevision] when a newer one is ready. Provider categories are never modified.
     */
    private fun serviceGroups(): Map<Pair<StreamingService, ContentKind>, List<MediaItem>> {
        val snap = serviceGroupSnapshot
        if (snap != null && snap.items === cachedItems && snap.categories === cachedCategories &&
            snap.index === serviceIndex
        ) return snap.groups
        scheduleServiceGroupCompute()
        return snap?.groups ?: emptyMap()
    }

    private fun computeServiceGroups(
        items: List<MediaItem>,
        cats: List<Category>,
        idx: ServiceIndex?
    ): Map<Pair<StreamingService, ContentKind>, List<MediaItem>> {
        val names = HashMap<String, String>(cats.size * 2)
        for (c in cats) names[c.id] = c.name
        val extra: ((MediaItem) -> Set<StreamingService>)? =
            idx?.takeIf { !it.isEmpty() }?.let { index -> { item -> index.lookup(item) } }
        return runCatching {
            StreamingServices.group(items, extra) { item -> item.categoryId?.let { names[it] } ?: item.groupTitle }
        }.getOrDefault(emptyMap())
    }

    private fun scheduleServiceGroupCompute() {
        if (deferCatalogWorkForPlayback()) return
        if (!serviceComputeRunning.compareAndSet(false, true)) return
        repoScope.launch {
            if (deferCatalogWorkForPlayback()) {
                serviceComputeRunning.set(false)
                return@launch
            }
            var changed = false
            try {
                while (true) {
                    val items = cachedItems
                    val cats = cachedCategories
                    val idx = serviceIndex
                    val groups = computeServiceGroups(items, cats, idx)
                    val prev = serviceGroupSnapshot
                    serviceGroupSnapshot = ServiceGroupSnapshot(items, cats, idx, groups)
                    if (prev == null || groupSignature(prev.groups) != groupSignature(groups)) changed = true
                    if (items === cachedItems && cats === cachedCategories && idx === serviceIndex) break
                }
            } finally {
                serviceComputeRunning.set(false)
            }
            if (changed) bumpRevision()
        }
    }

    private fun groupSignature(g: Map<Pair<StreamingService, ContentKind>, List<MediaItem>>): Map<String, Int> =
        g.entries.associate { "${it.key.first.key}|${it.key.second}" to it.value.size }

    private class TopRatedSnapshot(
        val items: List<MediaItem>,
        val index: ServiceIndex?,
        val vod: List<MediaItem>,
        val series: List<MediaItem>
    )

    @Volatile
    private var topRatedSnapshot: TopRatedSnapshot? = null
    private val topRatedComputeRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var newlyAddedMoviesSnap: List<MediaItem> = emptyList()
    @Volatile
    private var newlyAddedSeriesSnap: List<MediaItem> = emptyList()
    @Volatile
    private var providerCountSnap: Map<String, Int> = emptyMap()
    @Volatile
    private var derivedForItems: List<MediaItem>? = null
    private val derivedComputeRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Rated titles of [kind], highest first, capped at [CatalogCategories.TOP_RATED_LIMIT]. */
    fun topRated(kind: ContentKind): List<MediaItem> {
        val items = cachedItems
        val idx = serviceIndex
        val snap = topRatedSnapshot
        if (snap != null && snap.items === items && snap.index === idx) {
            return if (kind == ContentKind.SERIES) snap.series else snap.vod
        }
        // Never sort the whole catalog on the caller / UI thread — that ANR'd low-memory TV boxes
        // in 1.4.97 after the TMDB lists landed. Return the last result (or empty) immediately.
        scheduleTopRatedCompute()
        if (snap != null) {
            return if (kind == ContentKind.SERIES) snap.series else snap.vod
        }
        return emptyList()
    }

    @Volatile
    private var hasEffectiveTmdbKey = false

    /**
     * When a TMDB key is set, Top rated / the Top rated sort / poster stars use
     * TMDB scores only (or nothing, if the index has not loaded yet). Provider
     * ratings are used only when no key is set.
     */
    private fun tmdbScoreFn(idx: ServiceIndex?): ((MediaItem) -> Double?)? {
        if (!tmdbRatingsEnabled()) return null
        if (idx != null) return { item -> idx.ratingScore(item) }
        return { null }
    }

    /** Public lookup used by Desktop home ranking and any caller that needs the same rules as Top rated. */
    fun tmdbScoreLookup(): ((MediaItem) -> Double?)? = tmdbScoreFn(serviceIndex)

    /**
     * Poster / detail badge text: TMDB (50+ votes) when a key is set, otherwise the
     * provider rating. Null means no star. Does not fall back to the provider when
     * a key is set but TMDB has no qualifying score.
     */
    fun displayRatingOf(item: MediaItem): String? {
        if (item.kind == ContentKind.LIVE) return null
        val tmdbOnly = tmdbRatingsEnabled()
        val score = if (tmdbOnly) serviceIndex?.ratingScore(item) else null
        return Ratings.displayText(item, score, tmdbOnly = tmdbOnly)
    }

    private fun tmdbRatingsEnabled(): Boolean =
        hasEffectiveTmdbKey || BuildConfig.TMDB_DEFAULT_KEY.isNotBlank()

    private fun scheduleTopRatedCompute() {
        if (deferCatalogWorkForPlayback()) return
        if (!topRatedComputeRunning.compareAndSet(false, true)) return
        repoScope.launch(Dispatchers.Default) {
            if (deferCatalogWorkForPlayback()) {
                topRatedComputeRunning.set(false)
                return@launch
            }
            var changed = false
            try {
                while (true) {
                    val items = cachedItems
                    val idx = serviceIndex
                    val tmdb = tmdbScoreFn(idx)
                    val (vod, series) = Ratings.topRatedBoth(
                        items,
                        CatalogCategories.TOP_RATED_LIMIT,
                        tmdb
                    )
                    val prev = topRatedSnapshot
                    topRatedSnapshot = TopRatedSnapshot(items, idx, vod, series)
                    if (prev == null || prev.vod != vod || prev.series != series) changed = true
                    if (items === cachedItems && idx === serviceIndex) break
                }
            } finally {
                topRatedComputeRunning.set(false)
            }
            if (changed) bumpRevision()
        }
    }

    private fun orderedCategories(kind: ContentKind, base: List<Category>): List<Category> {
        val hasItems = if (kind == ContentKind.SERIES) hasSeriesItems() else hasMovieItems()
        if (!hasItems) return base
        return CatalogCategories.build(
            kind = kind,
            provider = base,
            hasItems = true,
            hasRated = topRated(kind).isNotEmpty(),
            serviceCategories = StreamingServices.categoriesFor(kind, serviceGroups())
        )
    }

    fun categories(kind: ContentKind? = null): List<Category> {
        val base = if (kind == null) cachedCategories else cachedCategories.filter { it.kind == kind }
        return when (kind) {
            ContentKind.VOD -> orderedCategories(ContentKind.VOD, base)
            ContentKind.SERIES -> orderedCategories(ContentKind.SERIES, base)
            ContentKind.LIVE -> base
            null -> {
                var out = base
                if (hasMovieItems() && out.none { it.id == NEWLY_ADDED_VOD_CATEGORY_ID }) {
                    val synthetic = Category(
                        id = NEWLY_ADDED_VOD_CATEGORY_ID,
                        name = NEWLY_ADDED_VOD_CATEGORY_NAME,
                        kind = ContentKind.VOD
                    )
                    val idx = out.indexOfFirst { it.kind == ContentKind.VOD }
                    out = if (idx < 0) out + synthetic else out.take(idx) + synthetic + out.drop(idx)
                }
                if (hasSeriesItems() && out.none { it.id == NEWLY_ADDED_SERIES_CATEGORY_ID }) {
                    val synthetic = Category(
                        id = NEWLY_ADDED_SERIES_CATEGORY_ID,
                        name = NEWLY_ADDED_VOD_CATEGORY_NAME,
                        kind = ContentKind.SERIES
                    )
                    val idx = out.indexOfFirst { it.kind == ContentKind.SERIES }
                    out = if (idx < 0) out + synthetic else out.take(idx) + synthetic + out.drop(idx)
                }
                out
            }
        }
    }

    /**
     * Items for a category. Synthetic [NEWLY_ADDED_VOD_CATEGORY_ID] returns the newest
     * movie titles (excludes series-named categories), sorted by Xtream `added` when
     * present, else descending stream id / end-of-list heuristic.
     */
    fun itemsForCategory(categoryId: String): List<MediaItem> {
        if (categoryId == NEWLY_ADDED_VOD_CATEGORY_ID) {
            return newlyAddedMovies(NEWLY_ADDED_LIMIT)
        }
        if (categoryId == NEWLY_ADDED_SERIES_CATEGORY_ID) {
            return newlyAddedSeries(NEWLY_ADDED_LIMIT)
        }
        if (categoryId == CatalogCategories.TOP_RATED_VOD_ID) return topRated(ContentKind.VOD)
        if (categoryId == CatalogCategories.TOP_RATED_SERIES_ID) return topRated(ContentKind.SERIES)
        StreamingServices.parseCategoryId(categoryId)?.let { key ->
            return serviceGroups()[key].orEmpty()
        }
        val knownKind = cachedCategories.find { it.id == categoryId }?.kind
        if (knownKind == ContentKind.LIVE ||
            categoryId.startsWith("live-") ||
            categoryId.startsWith("m3u-live-")
        ) {
            return LiveChannelMapping.filterLiveChannels(cachedItems, categoryId)
        }
        return cachedItems.filter { it.categoryId == categoryId }
    }

    /** Newest movie titles for the Movies "Newly added" rail. */
    fun newlyAddedMovies(limit: Int = NEWLY_ADDED_LIMIT): List<MediaItem> {
        if (derivedForItems !== cachedItems) scheduleDerivedCatalog()
        val snap = newlyAddedMoviesSnap
        return if (limit >= snap.size) snap else snap.take(limit)
    }

    /** Newest series titles for the Series "Newly added" rail. */
    fun newlyAddedSeries(limit: Int = NEWLY_ADDED_LIMIT): List<MediaItem> {
        if (derivedForItems !== cachedItems) scheduleDerivedCatalog()
        val snap = newlyAddedSeriesSnap
        return if (limit >= snap.size) snap else snap.take(limit)
    }

    /**
     * Precompute Newly added lists and provider category counts on the thread that
     * just published [cachedItems] (IO). The UI must not scan the full catalog.
     */
    private fun refreshDerivedCatalog() {
        val items = cachedItems.toList()
        val cats = cachedCategories.toList()
        newlyAddedMoviesSnap = sortByRecentlyAdded(
            items.filter { item ->
                item.kind == ContentKind.VOD && item.categoryId != NEWLY_ADDED_VOD_CATEGORY_ID
            }
        ).take(NEWLY_ADDED_LIMIT)
        newlyAddedSeriesSnap = sortByRecentlyAdded(
            items.filter { item ->
                item.kind == ContentKind.SERIES && item.categoryId != NEWLY_ADDED_SERIES_CATEGORY_ID
            }
        ).take(NEWLY_ADDED_LIMIT)
        val counts = HashMap<String, Int>()
        for (item in items) {
            val id = item.categoryId ?: continue
            counts[id] = (counts[id] ?: 0) + 1
        }
        for (cat in cats) {
            if (cat.kind == ContentKind.LIVE) {
                counts[cat.id] = LiveChannelMapping.filterLiveChannels(items, cat.id).size
            }
        }
        providerCountSnap = counts
        derivedForItems = items
        scheduleTopRatedCompute()
        scheduleServiceGroupCompute()
        maybeStartServiceLookup()
    }

    private fun scheduleDerivedCatalog() {
        if (derivedForItems === cachedItems) return
        if (deferCatalogWorkForPlayback()) return
        if (!derivedComputeRunning.compareAndSet(false, true)) return
        repoScope.launch {
            if (deferCatalogWorkForPlayback()) {
                derivedComputeRunning.set(false)
                return@launch
            }
            try {
                if (derivedForItems !== cachedItems) {
                    refreshDerivedCatalog()
                    bumpRevision()
                }
            } finally {
                derivedComputeRunning.set(false)
            }
        }
    }

    /** Instant count for the group rail; never walks the whole catalog on the UI thread. */
    fun itemCountForCategory(categoryId: String): Int {
        if (categoryId == NEWLY_ADDED_VOD_CATEGORY_ID) return newlyAddedMovies().size
        if (categoryId == NEWLY_ADDED_SERIES_CATEGORY_ID) return newlyAddedSeries().size
        if (categoryId == CatalogCategories.TOP_RATED_VOD_ID) return topRated(ContentKind.VOD).size
        if (categoryId == CatalogCategories.TOP_RATED_SERIES_ID) return topRated(ContentKind.SERIES).size
        StreamingServices.parseCategoryId(categoryId)?.let { key ->
            return serviceGroups()[key]?.size ?: 0
        }
        if (derivedForItems !== cachedItems) scheduleDerivedCatalog()
        return providerCountSnap[categoryId] ?: 0
    }

    /** Featured hero candidate: newest movie (fallback any VOD/SERIES). */
    fun featuredHeroItem(): MediaItem? {
        newlyAddedMovies(1).firstOrNull()?.let { return it }
        newlyAddedSeries(1).firstOrNull()?.let { return it }
        return cachedItems.firstOrNull { it.kind == ContentKind.VOD || it.kind == ContentKind.SERIES }
    }

    /**
     * Sort key for "most recently added":
     * 1) [MediaItem.addedMs] from Xtream `added` when any item has it
     * 2) else descending [MediaItem.xtreamStreamId] (newer IDs tend to be newer titles)
     * 3) else reverse catalog order (end of list / last parsed).
     */
    fun sortByRecentlyAdded(items: List<MediaItem>): List<MediaItem> {
        // Live: always prefer Xtream `num` (provider lineup). Sorting live by `added` /
        // stream_id made the visible row disagree with the stream the user expected.
        val allLive = items.isNotEmpty() && items.all { it.kind == ContentKind.LIVE }
        if (allLive) {
            return LiveChannelMapping.sortLiveChannels(items)
        }
        val anyAdded = items.any { (it.addedMs ?: 0L) > 0L }
        if (anyAdded) {
            return items.sortedWith(
                compareByDescending<MediaItem> { it.addedMs ?: 0L }
                    .thenByDescending { it.xtreamStreamId ?: 0 }
            )
        }
        val anySid = items.any { (it.xtreamStreamId ?: 0) > 0 }
        if (anySid) {
            return items.sortedByDescending { it.xtreamStreamId ?: 0 }
        }
        return items.asReversed()
    }

    /**
     * Re-bind a UI-clicked item to the catalog entry so streamUrl/id cannot drift from filters/sort.
     * For Xtream LIVE, always rebuild the playback URL from [MediaItem.xtreamStreamId] + active
     * credentials — never trust a possibly-stale streamUrl, and never use channel `num`.
     */
    fun playableFrom(item: MediaItem): MediaItem {
        val canonical = itemById(item.id) ?: item
        val creds = activeXtreamCreds
        // Prefer the call-site MediaItem (Guide row / grid click) for stream_id + display name.
        // itemById alone previously let a catalog alias rebuild the wrong /live/.../{sid}.m3u8.
        val sid = item.xtreamStreamId?.takeIf { it > 0 }
            ?: canonical.xtreamStreamId?.takeIf { it > 0 }
        val displayName = item.name.ifBlank { canonical.name }
        if (item.kind == ContentKind.LIVE &&
            item.name.isNotBlank() &&
            canonical.name.isNotBlank() &&
            !item.name.equals(canonical.name, ignoreCase = true)
        ) {
            android.util.Log.e(
                "TotalIPTV.Live",
                "playableFrom NAME conflict id=${item.id} click=${item.name} catalog=${canonical.name} " +
                    "clickSid=${item.xtreamStreamId} catalogSid=${canonical.xtreamStreamId}"
            )
        }
        if (item.xtreamStreamId != null && canonical.xtreamStreamId != null &&
            item.xtreamStreamId != canonical.xtreamStreamId
        ) {
            android.util.Log.e(
                "TotalIPTV.Live",
                "playableFrom SID conflict id=${item.id} clickSid=${item.xtreamStreamId} catalogSid=${canonical.xtreamStreamId}"
            )
        }
        if ((item.kind == ContentKind.LIVE || canonical.kind == ContentKind.LIVE) &&
            creds != null && sid != null && sid > 0
        ) {
            val host = creds.baseUrl.trim().removeSuffix("/")
            val rebuilt = "$host/live/${creds.username}/${creds.password}/$sid.m3u8"
            return canonical.copy(
                id = item.id.ifBlank { canonical.id },
                name = displayName,
                streamUrl = rebuilt,
                xtreamStreamId = sid,
                channelNum = item.channelNum ?: canonical.channelNum,
                logoUrl = item.logoUrl ?: canonical.logoUrl,
                epgChannelId = item.epgChannelId ?: canonical.epgChannelId,
                kind = ContentKind.LIVE
            )
        }
        if (canonical.streamUrl.isNotBlank()) {
            return if (displayName.isNotBlank()) canonical.copy(name = displayName) else canonical
        }
        return item
    }

    /**
     * Sort a category's items. "Newly added" and "Top rated" already come in their own order,
     * so the default (Newest) sort keeps it instead of re-sorting.
     */
    fun sortCategoryItems(categoryId: String?, items: List<MediaItem>, mode: CatalogSort): List<MediaItem> =
        if (mode == CatalogSort.RECENTLY_ADDED && CatalogCategories.isOrderedVirtual(categoryId)) items
        else sortCatalogItems(items, mode)

    // ---- TMDB streaming-service lookup --------------------------------------------------

    private val _serviceLookupStatus = MutableStateFlow("")
    /** Short user-facing status for Settings ("Updated …", "Looking up 3/12…"). Never contains the key. */
    val serviceLookupStatus: StateFlow<String> = _serviceLookupStatus.asStateFlow()

    @Volatile
    private var serviceLookupJob: Job? = null
    private val serviceLookupDeferral = ServiceLookupDeferral()
    private val catalogWorkDeferredForPlayback = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun deferCatalogWorkForPlayback(): Boolean {
        if (com.totaliptv.pro.data.PlaybackGate.refreshAllowed()) return false
        catalogWorkDeferredForPlayback.set(true)
        return true
    }

    private suspend fun awaitPlaybackIdle(tag: String) {
        if (com.totaliptv.pro.data.PlaybackGate.refreshAllowed()) return
        Log.i(tag, "paused for playback")
        com.totaliptv.pro.data.PlaybackGate.active.first { playing -> !playing }
        Log.i(tag, "resumed after playback")
    }


    private fun serviceCacheFile(): File? = vodCacheDir?.let { File(it, "tmdb-services.json") }

    /**
     * Load cached TMDB service lists (7-day cache) and refresh them in the background when
     * stale or when [force] is set. Movies / Series never wait on this.
     * A FullRecheck is not started until the catalog has movie or series items,
     * unless [force] is set (Settings).
     */
    fun startServiceLookup(force: Boolean = false) {
        val running = serviceLookupJob
        if (running?.isActive == true) {
            if (!force) return
            running.cancel()
        }
        serviceLookupJob = repoScope.launch {
            runCatching { runServiceLookup(force) }.onFailure { t ->
                if (t is CancellationException) throw t
                Log.w(SVC_TAG, "service lookup failed: ${t.javaClass.simpleName}")
                _serviceLookupStatus.value = "Service lookup failed (${t.javaClass.simpleName})"
            }
        }
    }

    /** Apply a saved service index with no network. Safe to call from Application.onCreate. */
    fun preloadServiceIndexFromCache() {
        repoScope.launch {
            val file = serviceCacheFile() ?: return@launch
            val loaded = runCatching { TmdbServiceLookup.readUsableCache(file) }.getOrNull()
            if (loaded != null && loaded.entries.isNotEmpty() && serviceIndex == null) {
                applyServiceIndex(loaded.entries)
                Log.i(SVC_TAG, "preloaded service index entries=${loaded.entries.size} complete=${loaded.complete}")
            }
        }
    }

    private fun maybeStartServiceLookup() {
        if (hasMovieItems() || hasSeriesItems()) startServiceLookup(force = false)
    }

    private fun persistServiceCache(
        file: File,
        fp: String,
        entries: List<com.totaliptv.pro.data.tmdb.TmdbServiceEntry>,
        progress: List<com.totaliptv.pro.data.tmdb.TmdbQueryCursor>,
        complete: Boolean,
        fetchedAtMs: Long,
        completedServices: Collection<String>,
        fullRecheck: Boolean
    ): Boolean {
        if (entries.isEmpty()) return false
        val completed = completedServices.toList().sorted()
        val ok = TmdbServiceLookup.writeCache(
            file,
            TmdbServiceCache(
                fetchedAtMs = fetchedAtMs,
                keyFingerprint = fp,
                entries = entries,
                complete = complete,
                progress = if (complete) emptyList() else progress,
                queriedServices = if (complete) TmdbServiceLookup.serviceKeys().toList().sorted() else completed,
                appVersion = com.totaliptv.pro.BuildConfig.VERSION_NAME,
                completedServices = completed,
                fullRecheck = fullRecheck && !complete
            )
        )
        if (ok) {
            Log.i(SVC_TAG, "service cache write ok bytes=${file.length()} complete=$complete entries=${entries.size}")
        } else {
            Log.w(SVC_TAG, "service cache write failed complete=$complete entries=${entries.size}")
        }
        return ok
    }
    private suspend fun runServiceLookup(force: Boolean) {
        if (!force && !hasMovieItems() && !hasSeriesItems()) {
            Log.i(SVC_TAG, "service lookup skipped; catalog not ready")
            return
        }
        val file = serviceCacheFile() ?: return
        ensurePlaybackWatch()
        try {
            val tight = memoryTight
            val persistEvery = MemoryBudget.persistEveryPages(tight)
            val applyEvery = MemoryBudget.serviceIndexApplyEveryPersists(tight)
            data class LookupStart(
                val key: String,
                val fp: String,
                val plan: TmdbServiceLookup.FetchPlan,
                val seed: List<com.totaliptv.pro.data.tmdb.TmdbServiceEntry>,
            )
            val start = run {
                val key = prefs.effectiveTmdbApiKey()
                hasEffectiveTmdbKey = key.isNotBlank()
                val fp = TmdbServiceLookup.fingerprint(key)
                // Show last lists immediately, even if the cache version is older (1.4.97 bumped it
                // and forced a long first-run fetch that locked up TV boxes).
                val usable = TmdbServiceLookup.readUsableCache(file)
                if (usable != null && usable.entries.isNotEmpty() && serviceIndex == null) {
                    applyServiceIndex(usable.entries)
                }
                if (key.isBlank()) {
                    _serviceLookupStatus.value = if (usable != null) "Using saved service lists (no TMDB key set)"
                    else "Add a TMDB API key to fill Netflix / Prime Video / HBO / Max / Apple TV+ / Hulu / Disney+ / Paramount+"
                    return
                }
                val now = System.currentTimeMillis()
                // Prefer the versioned read, but never start from zero if an older/usable file exists.
                val current = TmdbServiceLookup.readCache(file) ?: usable
                val plan = TmdbServiceLookup.planFetch(current, fp, now, force = force)
                if (!force && plan.isNoop) {
                    Log.i(SVC_TAG, "service cache fresh entries=${current!!.entries.size} ageMs=${now - current.fetchedAtMs}")
                    _serviceLookupStatus.value = "Service groups updated " + formatShortDate(current.fetchedAtMs) +
                        " (${serviceIndex?.size ?: 0} titles)"
                    return
                }
                Log.i(SVC_TAG, "service cache refresh reason=${plan.reason} kind=${plan.kind} entries=${current?.entries?.size ?: 0} queries=${plan.queries.size} persistEvery=$persistEvery tight=$tight")
                if (!com.totaliptv.pro.data.PlaybackGate.refreshAllowed()) {
                    serviceLookupDeferral.mark(force)
                    _serviceLookupStatus.value = serviceLookupDeferral.pausedStatus(force)
                    Log.i(SVC_TAG, "service lookup deferred; playback active force=$force")
                    return
                }
                LookupStart(key, fp, plan, (usable ?: current)?.entries.orEmpty())
            }
            val key = start.key
            val fp = start.fp
            val plan = start.plan
            val seed = start.seed
            _serviceLookupStatus.value = plan.queries.firstOrNull()?.let { TmdbServiceLookup.updatingStatus(it) }
                ?: "Updating service groups…"
            val lookup = TmdbServiceLookup(http)
            val lookupJob = kotlin.coroutines.coroutineContext[Job]
            val startedAt = plan.fetchedAtMs
            val completed = plan.completedServices.toMutableSet()
            var lastSavedOk = false
            var persistCount = 0
            var lastApplied = 0
            val result = try {
                lookup.fetch(
                    apiKey = key,
                    seed = seed,
                    progress = plan.progress,
                    queries = plan.queries,
                    persistEveryPages = persistEvery,
                    onQueryStart = { status ->
                        _serviceLookupStatus.value = status
                    },
                    onServiceComplete = { serviceKey ->
                        if (serviceKey != null) completed += serviceKey
                    },
                    onPersist = { entries, progress, complete ->
                        if (com.totaliptv.pro.data.PlaybackGate.refreshAllowed()) {
                            lastSavedOk = persistServiceCache(
                                file, fp, entries, progress, complete, startedAt,
                                completedServices = completed,
                                fullRecheck = plan.fullRecheck
                            )
                            persistCount++
                            if (entries.isNotEmpty() &&
                                (complete || persistCount == 1 || persistCount - lastApplied >= applyEvery)
                            ) {
                                applyServiceIndex(entries)
                                lastApplied = persistCount
                            }
                        }
                    },
                    isActive = { lookupJob?.isActive != false },
                    shouldPause = { !com.totaliptv.pro.data.PlaybackGate.refreshAllowed() }
                )
            } catch (e: TmdbServiceLookup.LookupFailed) {
                _serviceLookupStatus.value = e.message ?: "TMDB lookup failed"
                return
            }
            if (result.entries.isEmpty()) {
                _serviceLookupStatus.value = if (serviceIndex != null) {
                    "Service lookup failed; using saved lists"
                } else {
                    "TMDB returned no service lists (check network)"
                }
                return
            }
            if (result.complete) {
                completed += TmdbServiceLookup.serviceKeys()
            }
            val saved = persistServiceCache(
                file, fp, result.entries, result.progress, result.complete, startedAt,
                completedServices = completed,
                fullRecheck = plan.fullRecheck
            ) || lastSavedOk
            if (!result.complete) {
                if (result.paused) {
                    serviceLookupDeferral.mark(force)
                    Log.i(SVC_TAG, "service lookup paused for playback entries=${result.entries.size} force=$force")
                    _serviceLookupStatus.value = serviceLookupDeferral.pausedStatus(force)
                } else {
                    Log.i(SVC_TAG, "service lookup incomplete entries=${result.entries.size}")
                    _serviceLookupStatus.value = "Service lookup paused; saved progress " +
                        "(${result.entries.size} titles)"
                }
                if (com.totaliptv.pro.data.PlaybackGate.refreshAllowed()) {
                    applyServiceIndex(result.entries)
                }
                return
            }
            applyServiceIndex(result.entries)
            _serviceLookupStatus.value = if (saved) {
                "Service groups updated " + formatShortDate(startedAt) +
                    " (${serviceIndex?.size ?: 0} titles)"
            } else {
                ServiceLookupDeferral.SAVE_FAILED
            }
        } catch (oom: OutOfMemoryError) {
            runCatching { System.gc() }
            Log.w(SVC_TAG, "service lookup ran out of memory; keeping saved lists")
            _serviceLookupStatus.value = "Service lookup ran out of memory; using saved lists"
        }
    }

    /** Build the in-memory index off the UI thread. Never throws on OOM — keep the last index. */
    private fun applyServiceIndex(entries: List<com.totaliptv.pro.data.tmdb.TmdbServiceEntry>): Boolean {
        if (entries.isEmpty()) return false
        return try {
            val index = ServiceIndex(entries)
            serviceIndex = index
            bumpRevision()
            true
        } catch (oom: OutOfMemoryError) {
            runCatching { System.gc() }
            Log.w(SVC_TAG, "service index ran out of memory; keeping previous lists")
            false
        }
    }

    private fun formatShortDate(ms: Long): String =
        java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date(ms))

    fun sortCatalogItems(items: List<MediaItem>, mode: CatalogSort): List<MediaItem> =
        when (mode) {
            CatalogSort.AZ -> items.sortedBy { it.name.lowercase() }
            CatalogSort.ZA -> items.sortedByDescending { it.name.lowercase() }
            CatalogSort.RECENTLY_ADDED -> sortByRecentlyAdded(items)
            CatalogSort.TOP_RATED -> Ratings.sortTopRated(items, tmdbScoreFn(serviceIndex))
        }

    private fun hasVodItems(): Boolean =
        cachedItems.any { it.kind == ContentKind.VOD || it.kind == ContentKind.SERIES }

    private fun hasMovieItems(): Boolean =
        cachedItems.any { it.kind == ContentKind.VOD }

    private fun hasSeriesItems(): Boolean =
        cachedItems.any { it.kind == ContentKind.SERIES }

    private fun looksLikeSeriesName(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("series") ||
            n.contains("tv show") ||
            n.contains("tvshow") ||
            n.contains("saison") ||
            n.contains("сериал") ||
            (n.contains("show") && !n.contains("showcase"))
    }

    fun liveItems(limit: Int = Int.MAX_VALUE): List<MediaItem> {
        val live = LiveChannelMapping.filterLiveChannels(cachedItems)
        return if (limit == Int.MAX_VALUE) live else live.take(limit)
    }

    /** All in-memory items for a content kind (excludes synthetic Newly-added category ids). */
    fun itemsByKind(kind: ContentKind): List<MediaItem> =
        cachedItems.filter { it.kind == kind }

    fun itemById(id: String): MediaItem? = cachedItems.find { it.id == id }

    /**
     * Case-insensitive name search across live + VOD (movies/series) in-memory catalog.
     * Always searches both kinds: reserves ~half the [limit] for LIVE and ~half for VOD
     * so a huge live catalog cannot crowd out movies. Short-circuits once both quotas
     * are filled; sorts only the small result lists. Safe on a background dispatcher.
     */
    fun searchCatalog(query: String, limit: Int = 80): List<MediaItem> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val snapshot = cachedItems
        val liveTarget = maxOf(1, limit / 2)
        val vodTarget = maxOf(1, limit - liveTarget)
        val liveHits = ArrayList<MediaItem>(liveTarget)
        val vodHits = ArrayList<MediaItem>(vodTarget)

        for (item in snapshot) {
            if (liveHits.size >= liveTarget && vodHits.size >= vodTarget) break
            if (!item.name.lowercase().contains(q)) continue
            when (item.kind) {
                ContentKind.LIVE -> if (liveHits.size < liveTarget) liveHits.add(item)
                ContentKind.VOD, ContentKind.SERIES ->
                    if (vodHits.size < vodTarget) vodHits.add(item)
            }
        }

        // Fill leftover slots from either kind so unused quota is not wasted.
        val used = liveHits.size + vodHits.size
        if (used < limit) {
            val seen = HashSet<String>(used * 2 + 8)
            liveHits.forEach { seen.add(it.id) }
            vodHits.forEach { seen.add(it.id) }
            var extra = limit - used
            for (item in snapshot) {
                if (extra <= 0) break
                if (item.id in seen) continue
                if (!item.name.lowercase().contains(q)) continue
                when (item.kind) {
                    ContentKind.LIVE -> liveHits.add(item)
                    ContentKind.VOD, ContentKind.SERIES -> vodHits.add(item)
                }
                seen.add(item.id)
                extra--
            }
        }

        if (liveHits.isEmpty() && vodHits.isEmpty()) return emptyList()
        liveHits.sortBy { it.name.lowercase() }
        vodHits.sortBy { it.name.lowercase() }
        val combined = liveHits + vodHits
        if (showAdultInSearchCached) return combined
        val byId = rawCategories.associateBy { it.id }
        return AdultSearchFilter.filter(combined, byId, showAdultInSearch = false)
    }

    /** Display label for search rows: Live / Movie / Series. */
    fun searchKindLabel(item: MediaItem): String = when (item.kind) {
        ContentKind.LIVE -> "Live"
        ContentKind.VOD -> "Movie"
        ContentKind.SERIES -> "Series"
    }

    /** True when Xtream VOD merge is still in progress (live may already be searchable). */
    fun isVodLoading(): Boolean = _vodLoading.value

    fun hasXtreamEpg(): Boolean = activeXtreamCreds != null


    /** Resolve first playable episode for a SERIES catalog item (keyed by episode id for resume). */
    suspend fun resolveSeriesPlayable(item: MediaItem): MediaItem? = withContext(Dispatchers.IO) {
        if (item.kind != ContentKind.SERIES) return@withContext item
        // Already an episode playable (Continue watching / prior resolve)
        if (item.id.startsWith("series-ep-") && item.streamUrl.contains("/series/")) {
            return@withContext item
        }
        val sid = item.xtreamStreamId ?: return@withContext null
        val creds = activeXtreamCreds ?: return@withContext null
        val resolved = xtreamApi.resolveSeriesEpisode(creds, sid) ?: return@withContext null
        toSeriesEpisodeItem(item, resolved)
    }

    /** Season/episode tree for series detail picker. */
    suspend fun resolveSeriesInfo(item: MediaItem): XtreamApi.SeriesInfo? = withContext(Dispatchers.IO) {
        if (item.kind != ContentKind.SERIES) return@withContext null
        val sid = item.xtreamStreamId ?: return@withContext null
        val creds = activeXtreamCreds ?: return@withContext null
        xtreamApi.fetchSeriesInfo(creds, sid)
    }

    /** Fill a missing episode plot from the built-in / user key when the provider left it blank. */
    suspend fun fillEpisodePlot(
        item: MediaItem,
        episode: XtreamApi.SeriesEpisode
    ): XtreamApi.SeriesEpisode = withContext(Dispatchers.IO) {
        episode.plot?.trim()?.takeIf { it.isNotBlank() }?.let { return@withContext episode }
        val key = runCatching { prefs.effectiveTmdbApiKey() }.getOrDefault("")
        if (key.isBlank()) return@withContext episode
        val plot = TmdbPlotLookup.fillEpisodeOverview(
            http,
            key,
            item.tmdbId,
            item.name,
            item.year,
            episode.season,
            episode.episodeNum
        )
        if (plot.isNullOrBlank()) episode else episode.copy(plot = plot)
    }

    /** Resolve a specific episode from get_series_info into a playable MediaItem. */
    suspend fun resolveSeriesEpisodePlayable(
        item: MediaItem,
        episodeId: Int
    ): MediaItem? = withContext(Dispatchers.IO) {
        if (item.kind != ContentKind.SERIES) return@withContext item
        val sid = item.xtreamStreamId ?: return@withContext null
        val creds = activeXtreamCreds ?: return@withContext null
        val info = xtreamApi.fetchSeriesInfo(creds, sid) ?: return@withContext null
        val resolved = info.findEpisode(episodeId) ?: return@withContext null
        toSeriesEpisodeItem(item, resolved)
    }

    /**
     * Next episode after the current episode leaf (series-ep-###), using the series catalog item.
     * [seriesCatalogId] is typically series-123 (EXTRA_CATALOG_ID).
     */
    suspend fun resolveNextSeriesEpisode(
        seriesCatalogId: String?,
        currentEpisodeLeafId: String
    ): MediaItem? = withContext(Dispatchers.IO) {
        val epId = currentEpisodeLeafId.removePrefix("series-ep-").toIntOrNull()
            ?: return@withContext null
        val seriesItem = seriesCatalogId?.let { itemById(it) }
            ?: itemById(currentEpisodeLeafId)
            ?: return@withContext null
        val parent = when {
            seriesItem.kind == ContentKind.SERIES && !seriesItem.id.startsWith("series-ep-") -> seriesItem
            seriesCatalogId != null -> itemById(seriesCatalogId)
            else -> null
        } ?: return@withContext null
        val sid = parent.xtreamStreamId ?: return@withContext null
        val creds = activeXtreamCreds ?: return@withContext null
        val info = xtreamApi.fetchSeriesInfo(creds, sid) ?: return@withContext null
        val next = info.nextAfter(epId) ?: return@withContext null
        toSeriesEpisodeItem(parent, next)
    }

    private fun toSeriesEpisodeItem(item: MediaItem, resolved: XtreamApi.SeriesEpisode): MediaItem {
        val parentId = item.seriesCatalogId
            ?: item.id.takeUnless { it.startsWith("series-ep-") }
        val seriesName = item.name.substringBefore(" — ").ifBlank { item.name }
        return item.copy(
            id = "series-ep-${resolved.episodeId}",
            streamUrl = resolved.url,
            name = SeriesTitles.label(seriesName, resolved.season, resolved.episodeNum, resolved.title),
            seriesCatalogId = parentId,
            xtreamStreamId = item.xtreamStreamId
        )
    }

    private fun forgetEpg() {
        epgCache.clear()
        epgCachedAt.clear()
        bulkCovered.clear()
        epgInFlight.clear()
        bulkGuideReady = false
        shortEpgAllowed = false
        bulkMemory = emptyMap()
    }

    private fun storePrograms(sid: Int, programs: List<EpgProgram>, nowMs: Long) {
        GuideWindow.trimChannelCache(epgCache, sid)
        GuideWindow.trimChannelCache(epgCachedAt, sid)
        epgCache[sid] = programs
        epgCachedAt[sid] = nowMs
    }

    /**
     * Whole xmltv listing kept in memory. epgCache is trimmed to a few hundred
     * channels, so without this every other row re-read the 1.4 MB file from disk.
     */
    @Volatile
    private var bulkMemory: Map<Int, List<EpgProgram>> = emptyMap()

    private fun rememberBulk(byStream: Map<Int, List<EpgProgram>>, nowMs: Long) {
        bulkMemory = byStream.filterValues { it.isNotEmpty() }
        for ((sid, programs) in byStream) {
            if (programs.isEmpty()) continue
            storePrograms(sid, programs, nowMs)
            bulkCovered.add(sid)
        }
    }

    private fun loadFreshShortCache(nowMs: Long) {
        val file = guideCacheDir?.let { File(it, GuideBulkCache.SHORT_FILE_NAME) } ?: return
        if (!GuideBulkCache.isFresh(file, nowMs)) return
        for ((sid, programs) in GuideBulkCache.read(file)) {
            if (epgCache.containsKey(sid)) continue
            storePrograms(sid, programs, nowMs)
        }
    }

    private fun restoreListing(sid: Int): List<EpgProgram>? {
        bulkMemory[sid]?.let { return it }
        val dir = guideCacheDir ?: return null
        val now = System.currentTimeMillis()
        val bulk = File(dir, GuideBulkCache.FILE_NAME)
        if (GuideBulkCache.isUsable(bulk, now)) {
            GuideBulkCache.readChannel(bulk, sid)?.let { return it }
        }
        val short = File(dir, GuideBulkCache.SHORT_FILE_NAME)
        if (GuideBulkCache.isFresh(short, now)) {
            return GuideBulkCache.readChannel(short, sid)
        }
        return null
    }

    private fun persistShort(sid: Int, programs: List<EpgProgram>) {
        val dir = guideCacheDir ?: return
        val file = File(dir, GuideBulkCache.SHORT_FILE_NAME)
        val current = if (file.isFile) GuideBulkCache.read(file).toMutableMap() else mutableMapOf()
        current[sid] = programs
        GuideBulkCache.write(file, current)
    }

    suspend fun resolvePoster(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val isVodOrSeries = item.kind == ContentKind.VOD || item.kind == ContentKind.SERIES
        val needsPoster = item.artworkUrl().isNullOrBlank()
        val needsTrailer = item.youtubeTrailer.isNullOrBlank() && isVodOrSeries
        val needsPlot = item.plot.isNullOrBlank() && isVodOrSeries
        val tmdbOnly = tmdbRatingsEnabled()
        fun stamped(it: MediaItem): MediaItem = Ratings.withMergedRating(
            it,
            serviceIndex?.ratingScore(it),
            overwriteProvider = tmdbOnly
        )
        // When a TMDB key is set, stars come from TMDB only, so do not fetch a
        // provider rating just to show a badge.
        val needsRating = !tmdbOnly && item.displayRating() == null && isVodOrSeries
        val needsYear = item.year == null && isVodOrSeries
        if (!needsPoster && !needsTrailer && !needsPlot && !needsRating && !needsYear) {
            return@withContext stamped(item)
        }
        val sid = item.xtreamStreamId ?: return@withContext stamped(item)
        val creds = activeXtreamCreds ?: return@withContext stamped(item)
        // Poster-only fast path when trailer/plot/rating already known
        if (needsPoster && !needsTrailer && !needsPlot && !needsRating) {
            val cached = posterMutex.withLock { posterCache[sid] }
            if (cached != null) {
                val withPoster = if (cached.isBlank()) item else item.copy(posterUrl = cached)
                return@withContext stamped(withPoster)
            }
        }
        val details = xtreamApi.fetchMediaDetails(creds, sid, item.kind == ContentKind.SERIES)
        if (!details.posterUrl.isNullOrBlank()) {
            posterMutex.withLock { posterCache[sid] = details.posterUrl }
        } else if (needsPoster) {
            posterMutex.withLock { posterCache[sid] = "" }
        }
        var out = item
        if (needsPoster && !details.posterUrl.isNullOrBlank()) {
            out = out.copy(posterUrl = details.posterUrl)
        }
        if (needsTrailer && !details.youtubeTrailer.isNullOrBlank()) {
            out = out.copy(youtubeTrailer = details.youtubeTrailer)
            // Keep catalog entry in sync so Preview works without another fetch
            updateItemTrailer(out.id, details.youtubeTrailer)
        }
        if (needsPlot && !details.plot.isNullOrBlank()) {
            out = out.copy(plot = details.plot)
        }
        if (needsRating && !details.rating.isNullOrBlank()) {
            out = out.copy(rating = details.rating)
        }
        if (needsYear && details.year != null) {
            out = out.copy(year = details.year)
        }
        if (isVodOrSeries && (out.plot.isNullOrBlank() || out.year == null)) {
            val key = runCatching { prefs.effectiveTmdbApiKey() }.getOrDefault("")
            if (key.isNotBlank()) {
                out = TmdbPlotLookup.fill(http, key, out)
            }
        }
        stamped(out)
    }

    /** Resolve youtube trailer for Preview; uses cached field or get_vod_info. */
    suspend fun resolveYoutubeTrailer(item: MediaItem): String? = withContext(Dispatchers.IO) {
        item.youtubeTrailer?.trim()?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        if (item.kind != ContentKind.VOD && item.kind != ContentKind.SERIES) return@withContext null
        val sid = item.xtreamStreamId ?: return@withContext null
        val creds = activeXtreamCreds ?: return@withContext null
        val details = xtreamApi.fetchMediaDetails(creds, sid, item.kind == ContentKind.SERIES)
        val trailer = details.youtubeTrailer?.trim()?.takeIf { it.isNotBlank() }
        if (trailer != null) updateItemTrailer(item.id, trailer)
        trailer
    }

    private fun updateItemTrailer(id: String, trailer: String) {
        cachedItems = withTrailer(cachedItems, id, trailer) ?: return
        withTrailer(rawItems, id, trailer)?.let { rawItems = it }
        rawItems.firstOrNull { it.id == id }?.kind?.takeIf { it != ContentKind.LIVE }?.let { dirtyKinds.add(it) }
    }

    private fun withTrailer(cur: List<MediaItem>, id: String, trailer: String): List<MediaItem>? {
        val i = cur.indexOfFirst { it.id == id }
        if (i < 0) return null
        val next = cur.toMutableList()
        next[i] = next[i].copy(youtubeTrailer = trailer)
        return next
    }

    suspend fun epgForChannel(item: MediaItem): EpgNowNext = withContext(Dispatchers.IO) {
        val programs = loadPrograms(item)
        xtreamApi.nowNextFromPrograms(programs)
    }

    /** Instant row from [epgCache] — no network. Used to paint Guide blocks immediately. */
    fun peekCachedGuideRow(channel: MediaItem): EpgChannelRow? {
        val sid = channel.xtreamStreamId ?: return null
        val cached = epgCache[sid] ?: bulkMemory[sid] ?: return null
        val bound = LiveEpgBinding.bindForDisplay(channel, cached, liveSiblings())
        return EpgChannelRow(
            channel = channel,
            programs = bound,
            nowNext = xtreamApi.nowNextFromPrograms(bound)
        )
    }

    /**
     * Download xmltv.php once, keep the 18-hour window, and reuse it on disk.
     * A miss falls back to short EPG for visible rows only.
     */
    suspend fun prepareBulkGuideEpg(cacheDir: File) = withContext(Dispatchers.IO) {
        guideCacheDir = cacheDir
        val creds = activeXtreamCreds
        if (creds == null) {
            shortEpgAllowed = true
            return@withContext
        }
        val file = File(cacheDir, GuideBulkCache.FILE_NAME)
        val t0 = System.currentTimeMillis()
        try {
            if (bulkGuideReady) {
                if (!GuideBulkCache.isFresh(file, t0)) startBulkDownload(creds, file)
                return@withContext
            }
            if (GuideBulkCache.isUsable(file, t0)) {
                // Paint from the saved listings right away, even past 30 min;
                // an old file is refreshed in the background.
                rememberBulk(GuideBulkCache.read(file), t0)
                loadFreshShortCache(t0)
                bulkGuideReady = epgCache.isNotEmpty() || bulkCovered.isNotEmpty()
                if (bulkGuideReady) {
                    val fresh = GuideBulkCache.isFresh(file, t0)
                    if (!fresh) startBulkDownload(creds, file)
                    Log.i("TotalIPTV.Guide", "bulkFromDisk fresh=$fresh ms=${System.currentTimeMillis() - t0} channels=${bulkCovered.size}")
                    return@withContext
                }
            }
            // Nothing saved: wait briefly for xmltv, then visible rows use short EPG
            // while the download keeps going in the background.
            val job = startBulkDownload(creds, file)
            val ok = kotlinx.coroutines.withTimeoutOrNull(GuideBulkCache.FIRST_WAIT_MS) { job.await() }
            Log.i("TotalIPTV.Guide", "bulkFirstWait ok=$ok ms=${System.currentTimeMillis() - t0}")
            loadFreshShortCache(t0)
        } finally {
            shortEpgAllowed = true
        }
    }

    private val bulkLock = Any()
    @Volatile
    private var bulkJob: kotlinx.coroutines.Deferred<Boolean>? = null
    @Volatile
    private var bulkFailedAtMs = 0L
    private val _guideBulkRevision = MutableStateFlow(0)
    /** Bumps when a background xmltv download lands; the guide repaints from cache. */
    val guideBulkRevision: StateFlow<Int> = _guideBulkRevision.asStateFlow()

    /**
     * One xmltv download at a time, owned by the repository, so switching category
     * or leaving the guide joins it instead of starting another one.
     */
    @Volatile
    private var bulkCall: okhttp3.Call? = null
    @Volatile
    private var bulkPendingCreds: XtreamApi.Credentials? = null
    @Volatile
    private var bulkPendingFile: File? = null
    /** A refresh was skipped or stopped because a video started; run it once playback ends. */
    private val bulkDeferredForPlayback = java.util.concurrent.atomic.AtomicBoolean(false)
    private val playbackWatchStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Stop an in-flight guide download when a player opens; resume it when playback ends. */
    private fun ensurePlaybackWatch() {
        if (!playbackWatchStarted.compareAndSet(false, true)) return
        repoScope.launch {
            com.totaliptv.pro.data.PlaybackGate.active.collect { playing ->
                if (playing) {
                    val job = bulkJob
                    if (job != null && job.isActive) {
                        bulkDeferredForPlayback.set(true)
                        bulkCall?.cancel()
                        job.cancel()
                        Log.i("TotalIPTV.Guide", "bulkDownload paused for playback")
                    }
                    if (serviceLookupJob?.isActive == true) {
                        // Keep a forced refresh forced when the player interrupts it.
                        serviceLookupDeferral.mark(forceRequested = serviceLookupDeferral.forcePending())
                    }
                } else {
                    if (bulkDeferredForPlayback.getAndSet(false)) {
                        val c = bulkPendingCreds
                        val f = bulkPendingFile
                        if (c != null && f != null && activeXtreamCreds == c &&
                            !GuideBulkCache.isFresh(f, System.currentTimeMillis())
                        ) {
                            Log.i("TotalIPTV.Guide", "bulkDownload resumed after playback")
                            startBulkDownload(c, f)
                        }
                    }
                    val resumeForce = serviceLookupDeferral.consume()
                    if (resumeForce != null) {
                        Log.i(SVC_TAG, "service lookup resumed after playback force=$resumeForce")
                        startServiceLookup(force = resumeForce)
                    }
                    if (catalogWorkDeferredForPlayback.getAndSet(false)) {
                        Log.i(VOD_TAG, "catalog work resumed after playback")
                        persistVodCache(loadedForSourceId ?: "")
                        scheduleDerivedCatalog()
                        scheduleTopRatedCompute()
                        scheduleServiceGroupCompute()
                    }
                }
            }
        }
    }

    private fun startBulkDownload(creds: XtreamApi.Credentials, file: File): kotlinx.coroutines.Deferred<Boolean> {
        ensurePlaybackWatch()
        synchronized(bulkLock) {
            bulkJob?.takeIf { it.isActive }?.let { return it }
            bulkPendingCreds = creds
            bulkPendingFile = file
            if (!com.totaliptv.pro.data.PlaybackGate.refreshAllowed()) {
                // Never download the guide listing while a video plays.
                bulkDeferredForPlayback.set(true)
                return kotlinx.coroutines.CompletableDeferred(false)
            }
            if (System.currentTimeMillis() - bulkFailedAtMs < GuideBulkCache.RETRY_COOLDOWN_MS) {
                return kotlinx.coroutines.CompletableDeferred(false)
            }
            val job = repoScope.async {
                var ok = false
                for (attempt in 1..GuideBulkCache.DOWNLOAD_ATTEMPTS) {
                    ok = try {
                        downloadBulkOnce(creds, file)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w("TotalIPTV.Guide", "bulkDownload attempt=$attempt failed: ${t.javaClass.simpleName}")
                        false
                    }
                    if (ok || activeXtreamCreds != creds || bulkDeferredForPlayback.get()) break
                    if (attempt < GuideBulkCache.DOWNLOAD_ATTEMPTS) kotlinx.coroutines.delay(GuideBulkCache.RETRY_DELAY_MS)
                }
                if (!ok && !bulkDeferredForPlayback.get()) bulkFailedAtMs = System.currentTimeMillis()
                ok
            }
            bulkJob = job
            return job
        }
    }

    private suspend fun downloadBulkOnce(creds: XtreamApi.Credentials, file: File): Boolean {
        val t0 = System.currentTimeMillis()
        val start = GuideWindow.snapStart(t0)
        val end = GuideWindow.windowEndMs(start, GuideWindow.MAX_HOURS)
        val url = GuideBulkCache.xmltvUrl(creds.baseUrl, creds.username, creds.password)
        val bulkHttp = http.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(url).header("User-Agent", "TotalIPTVPro/1.4").get().build()
        val call = bulkHttp.newCall(req).also { bulkCall = it }
        val parsed = try {
            call.execute().use { resp ->
                if (resp.code == 429) throw XtreamApi.RateLimited("xmltv")
                if (!resp.isSuccessful) return@use emptyMap()
                val stream = resp.body?.byteStream() ?: return@use emptyMap()
                XmltvParser.parseStream(
                    stream,
                    isGzipHint = url.endsWith(".gz", ignoreCase = true),
                    windowStartMs = start,
                    windowEndMs = end,
                    keepDescription = false
                )
            }
        } catch (e: XtreamApi.RateLimited) {
            epgGate.onRateLimited()
            return false
        }
        if (activeXtreamCreds != creds) return false
        // A cancel (video started) cuts the stream and the parser returns a partial
        // listing. Never keep or save that; the full refresh runs again when idle.
        if (call.isCanceled() || kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == false ||
            bulkDeferredForPlayback.get() || !com.totaliptv.pro.data.PlaybackGate.refreshAllowed()
        ) {
            Log.i("TotalIPTV.Guide", "bulkDownload discarded partial (playback)")
            return false
        }
        val indexed = GuideBulkCache.indexByStream(parsed, liveSiblings())
        Log.i("TotalIPTV.Guide", "bulkDownload channels=${indexed.size} ms=${System.currentTimeMillis() - t0}")
        if (indexed.isEmpty()) return false
        rememberBulk(indexed, System.currentTimeMillis())
        runCatching { GuideBulkCache.write(file, indexed) }
        epgGate.onSuccess()
        bulkGuideReady = true
        _guideBulkRevision.value = _guideBulkRevision.value + 1
        return true
    }

    /** One channel’s EPG (cached or fetch). Caller updates that row; do not awaitAll the category. */
    suspend fun loadGuideRow(channel: MediaItem): EpgChannelRow = withContext(Dispatchers.IO) {
        fun cachedRow(): EpgChannelRow? {
            val sid = channel.xtreamStreamId ?: return null
            if (!GuideEpgLoad.cacheFresh(epgCachedAt[sid] ?: 0L, System.currentTimeMillis())) return null
            return peekCachedGuideRow(channel)
        }
        cachedRow()?.let { return@withContext it }
        val sid = channel.xtreamStreamId
        val now = System.currentTimeMillis()
        val covered = sid != null && sid in bulkCovered
        val inFlight = sid != null && sid in epgInFlight
        if (covered || !GuideEpgLoad.shouldFetchShort(
                cachedAtMs = sid?.let { epgCachedAt[it] } ?: 0L,
                nowMs = now,
                bulkCovers = covered,
                inFlight = inFlight,
                shortAllowed = shortEpgAllowed
            )
        ) {
            val programs = loadPrograms(channel)
            return@withContext EpgChannelRow(
                channel = channel,
                programs = programs,
                nowNext = xtreamApi.nowNextFromPrograms(programs)
            )
        }
        epgGate.acquire()
        try {
            cachedRow()?.let { return@withContext it }
            val programs = loadPrograms(channel)
            epgGate.onSuccess()
            EpgChannelRow(
                channel = channel,
                programs = programs,
                nowNext = xtreamApi.nowNextFromPrograms(programs)
            )
        } catch (e: XtreamApi.RateLimited) {
            epgGate.onRateLimited()
            peekCachedGuideRow(channel) ?: EpgChannelRow(channel = channel)
        } finally {
            epgGate.release()
        }
    }

    /**
     * Legacy batch helper. Prefer [loadGuideRow] + incremental UI updates.
     * Still waits for the whole slice — do not use from Guide first-paint.
     */
    suspend fun loadGuideRows(
        channels: List<MediaItem>,
        maxChannels: Int = Int.MAX_VALUE
    ): List<EpgChannelRow> = withContext(Dispatchers.IO) {
        val slice = if (maxChannels == Int.MAX_VALUE) channels else channels.take(maxChannels)
        if (activeXtreamCreds == null && xmltvUrl.isNullOrBlank()) {
            return@withContext slice.map {
                EpgChannelRow(channel = it, programs = emptyList(), nowNext = EpgNowNext())
            }
        }
        slice.map { loadGuideRow(it) }
    }

    private fun loadPrograms(item: MediaItem): List<EpgProgram> {
        val horizonStart = GuideWindow.snapStart(System.currentTimeMillis())
        val horizonEnd = GuideWindow.windowEndMs(horizonStart, GuideWindow.MAX_HOURS)
        val creds = activeXtreamCreds
        if (creds == null) {
            ensureXmltvLoaded()
            val id = item.epgChannelId?.trim()?.takeIf { it.isNotEmpty() }
            val named = item.name.trim().takeIf { it.isNotEmpty() }
            val raw = (id?.let { xmltvByChannel[it] } ?: named?.let { xmltvByChannel[it] }).orEmpty()
            val bound = LiveEpgBinding.bindForDisplay(item, raw, liveSiblings())
            return GuideWindow.retain(bound, horizonStart, horizonEnd)
        }
        // Always key EPG by Xtream stream_id — never channel num / list index / epg_channel_id string.
        val sid = item.xtreamStreamId ?: return emptyList()
        val now = System.currentTimeMillis()
        fun fromCache(programs: List<EpgProgram>): List<EpgProgram> {
            val bound = LiveEpgBinding.bindForDisplay(item, programs, liveSiblings())
            return GuideWindow.retain(bound, horizonStart, horizonEnd)
        }
        val cached = epgCache[sid]
        val cachedAt = epgCachedAt[sid] ?: 0L
        if (cached != null && GuideEpgLoad.cacheFresh(cachedAt, now)) {
            val bound = fromCache(cached)
            Log.d(
                "TotalIPTV.Guide",
                "epgCacheHit name=${item.name} id=${item.id} sid=$sid num=${item.channelNum} programs=${bound.size} first=${bound.firstOrNull()?.title}"
            )
            return bound
        }
        if (sid in bulkCovered) {
            val restored = cached ?: restoreListing(sid) ?: emptyList()
            if (cached == null) storePrograms(sid, restored, now)
            return fromCache(restored)
        }
        if (!GuideEpgLoad.shouldFetchShort(
                cachedAtMs = cachedAt,
                nowMs = now,
                bulkCovers = false,
                inFlight = sid in epgInFlight,
                shortAllowed = shortEpgAllowed
            )
        ) {
            return if (cached != null) fromCache(cached) else emptyList()
        }
        if (!epgInFlight.add(sid)) {
            val deadline = System.currentTimeMillis() + 20_000L
            while (sid in epgInFlight && System.currentTimeMillis() < deadline) {
                Thread.sleep(40)
            }
            val done = epgCache[sid]
            return if (done != null) fromCache(done) else emptyList()
        }
        try {
            val again = epgCache[sid]
            val againAt = epgCachedAt[sid] ?: 0L
            if (again != null && GuideEpgLoad.cacheFresh(againAt, System.currentTimeMillis())) {
                return fromCache(again)
            }
            val short = xtreamApi.fetchShortEpg(creds, sid, limit = GuideWindow.LISTING_LIMIT)
            val programs = if (short.isNotEmpty()) short else xtreamApi.fetchSimpleEpgTable(creds, sid)
            // Cache the horizon even when it is empty so a miss is not fetched on every scroll.
            val kept = GuideWindow.retain(programs, horizonStart, horizonEnd)
            val stamp = System.currentTimeMillis()
            storePrograms(sid, kept, stamp)
            runCatching { persistShort(sid, kept) }
            val bound = fromCache(kept)
            Log.i(
                "TotalIPTV.Guide",
                "epgBind name=${item.name} id=${item.id} sid=$sid num=${item.channelNum} epgCh=${item.epgChannelId} programs=${bound.size} first=${bound.firstOrNull()?.title}"
            )
            return bound
        } finally {
            epgInFlight.remove(sid)
        }
    }

    @Volatile
    private var liveSiblingsMemo: Pair<List<MediaItem>, List<MediaItem>>? = null

    /** Live items, filtered once per catalog (the guide calls this for every row). */
    private fun liveSiblings(): List<MediaItem> {
        val src = cachedItems
        liveSiblingsMemo?.let { (from, live) -> if (from === src) return live }
        val live = src.filter { it.kind == ContentKind.LIVE }
        liveSiblingsMemo = src to live
        return live
    }

    suspend fun toggleFavorite(item: MediaItem) {
        prefs.toggleFavorite(
            FavoriteRef(
                id = item.id,
                name = item.name,
                streamUrl = item.streamUrl,
                kind = item.kind,
                logoUrl = item.logoUrl ?: item.posterUrl
            )
        )
    }

    suspend fun isFavorite(id: String): Boolean = prefs.isFavorite(id)

    private fun downloadM3u(url: String): M3uParser.Result {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TotalIPTVPro/1.1")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Failed to download playlist: HTTP " + response.code)
            val stream = response.body?.byteStream() ?: error("Playlist download was empty")
            stream.bufferedReader(Charsets.UTF_8).use { reader -> M3uParser.parse(reader) }
        }
    }

    private fun ensureXmltvLoaded() {
        val url = xmltvUrl?.takeIf { it.isNotBlank() } ?: return
        if (xmltvLoaded) return
        if (deferCatalogWorkForPlayback()) return
        synchronized(xmltvLock) {
            if (deferCatalogWorkForPlayback()) return
            if (xmltvLoaded) return
            runCatching {
                val loaded = XmltvParser.loadFromUrl(url)
                val start = GuideWindow.snapStart(System.currentTimeMillis())
                val end = GuideWindow.windowEndMs(start, GuideWindow.MAX_HOURS)
                for ((id, programs) in loaded) {
                    val kept = GuideWindow.retain(programs, start, end)
                    if (kept.isNotEmpty()) xmltvByChannel[id] = kept
                }
            }
            xmltvLoaded = true
        }
    }

    private fun clearXmltv() {
        xmltvUrl = null
        xmltvLoaded = false
        xmltvByChannel.clear()
    }

    private fun downloadText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TotalIPTVPro/1.1")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Failed to download playlist: HTTP " + response.code)
            return response.body?.string().orEmpty()
        }
    }

    private fun isPermanentFailure(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            val msg = cur.message?.lowercase().orEmpty()
            if (msg.contains("auth=0") ||
                msg.contains("login rejected") ||
                msg.contains("missing m3u url") ||
                msg.contains("missing xtream url") ||
                msg.contains("missing username") ||
                msg.contains("missing password")
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    companion object {
        const val NEWLY_ADDED_VOD_CATEGORY_ID = CatalogCategories.NEWLY_ADDED_VOD_ID
        const val NEWLY_ADDED_SERIES_CATEGORY_ID = CatalogCategories.NEWLY_ADDED_SERIES_ID
        const val NEWLY_ADDED_VOD_CATEGORY_NAME = CatalogCategories.NEWLY_ADDED_NAME
        const val NEWLY_ADDED_LIMIT = 150
    }
}

/** In-memory browse sort for Live / Movies / Series grids. */
enum class CatalogSort {
    AZ,
    ZA,
    RECENTLY_ADDED,
    /** Highest rating first (provider, else TMDB); unrated at the bottom; ties A–Z. */
    TOP_RATED
}

private const val VOD_TAG = "TotalIPTV.Vod"
private const val SVC_TAG = "TotalIPTV.Services"
private const val VOD_ATTEMPTS = 3
/** An Update keeps a same-source movie/series download younger than this instead of restarting it. */
private const val VOD_KEEP_RUNNING_MS = 10 * 60_000L
private const val VOD_BACKOFF_MS = 2_000L
