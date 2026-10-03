package com.totaliptv.pro.data.repo

import android.util.Log
import com.totaliptv.pro.data.GuideBulkCache
import com.totaliptv.pro.data.LiveChannelMapping
import com.totaliptv.pro.data.SeriesTitles
import com.totaliptv.pro.data.LiveEpgBinding
import com.totaliptv.pro.data.local.AppPreferences
import com.totaliptv.pro.data.m3u.M3uParser
import com.totaliptv.pro.data.m3u.XmltvParser
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
    @Volatile
    private var cachedCategories: List<Category> = emptyList()

    @Volatile
    private var cachedItems: List<MediaItem> = emptyList()

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

    /** Bumps whenever the in-memory catalog changes (live publish or VOD merge). */
    private val _catalogRevision = MutableStateFlow(0)
    val catalogRevision: StateFlow<Int> = _catalogRevision.asStateFlow()

    /** True while Xtream VOD is still downloading/merging in the background. */
    private val _vodLoading = MutableStateFlow(false)
    val vodLoading: StateFlow<Boolean> = _vodLoading.asStateFlow()

    val sources: Flow<List<PlaylistSource>> = prefs.sources
    val favorites: Flow<List<FavoriteRef>> = prefs.favorites
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
            cachedCategories = emptyList()
            cachedItems = emptyList()
            loadedForSourceId = null
            activeXtreamCreds = null
            forgetEpg()
            clearXmltv()
            bumpRevision()
        }
    }

    suspend fun clearAllData() {
        vodJob?.cancel()
        vodJob = null
        _vodLoading.value = false
        prefs.clearAll()
        cachedCategories = emptyList()
        cachedItems = emptyList()
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

                if (!force && loadedForSourceId == source.id && cachedItems.isNotEmpty()) {
                    return@withContext cachedCategories to cachedItems
                }

                val hadCache = loadedForSourceId == source.id && cachedItems.isNotEmpty()
                var lastError: Throwable? = null
                val maxAttempts = 3

                for (attempt in 1..maxAttempts) {
                    try {
                        when (source.type) {
                            SourceType.M3U -> {
                                vodJob?.cancel()
                                vodJob = null
                                _vodLoading.value = false
                                activeXtreamCreds = null
                                val url = source.m3uUrl ?: error("Missing M3U URL")
                                val parsed = downloadM3u(url)
                                if (parsed.items.isEmpty()) error("Playlist contained no channels")
                                cachedCategories = parsed.categories
                                cachedItems = parsed.items
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
                                cachedCategories = live.categories
                                cachedItems = live.items
                                lastWarning = null
                                loadedForSourceId = source.id
                                bumpRevision()

                                val sourceId = source.id
                                scheduleVod = {
                                    vodJob?.cancel()
                                    _vodLoading.value = true
                                    vodJob = repoScope.launch {
                                        try {
                                            mergeVodInBackground(sourceId, base, user, pass)
                                            mergeSeriesInBackground(sourceId, base, user, pass)
                                        } finally {
                                            _vodLoading.value = false
                                        }
                                    }
                                }
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

    private suspend fun mergeVodInBackground(
        sourceId: String,
        base: String,
        user: String,
        pass: String
    ) {
        val vod = runCatching { xtreamApi.loadVodCatalog(base, user, pass) }
            .getOrElse { t ->
                val detail = SensitiveText.forUser(t)
                XtreamApi.VodCatalog(
                    emptyList(),
                    emptyList(),
                    listOf("VOD list failed (" + detail + "). Live TV still available.")
                )
            }

        loadMutex.withLock {
            if (loadedForSourceId != sourceId) return@withLock
            val liveCats = cachedCategories.filter { it.kind == ContentKind.LIVE }
            val liveItems = cachedItems.filter { it.kind == ContentKind.LIVE }
            cachedCategories = liveCats + vod.categories
            cachedItems = liveItems + vod.items
            lastWarning = vod.warnings.takeIf { it.isNotEmpty() }?.joinToString(" ")
            bumpRevision()
        }
    }


    private suspend fun mergeSeriesInBackground(
        sourceId: String,
        base: String,
        user: String,
        pass: String
    ) {
        val series = runCatching { xtreamApi.loadSeriesCatalog(base, user, pass) }
            .getOrElse { t ->
                val detail = SensitiveText.forUser(t)
                XtreamApi.SeriesCatalog(
                    emptyList(),
                    emptyList(),
                    listOf("Series list failed (" + detail + ").")
                )
            }

        loadMutex.withLock {
            if (loadedForSourceId != sourceId) return@withLock
            val keepCats = cachedCategories.filter { it.kind != ContentKind.SERIES }
            val keepItems = cachedItems.filter { it.kind != ContentKind.SERIES }
            cachedCategories = keepCats + series.categories
            cachedItems = keepItems + series.items
            val extra = series.warnings.takeIf { it.isNotEmpty() }?.joinToString(" ")
            if (!extra.isNullOrBlank()) {
                lastWarning = listOfNotNull(lastWarning, extra).joinToString(" ")
            }
            bumpRevision()
        }
    }
    private fun bumpRevision() {
        _catalogRevision.value = _catalogRevision.value + 1
    }

    fun categories(kind: ContentKind? = null): List<Category> {
        val base = if (kind == null) cachedCategories else cachedCategories.filter { it.kind == kind }
        return when (kind) {
            ContentKind.VOD -> {
                if (!hasMovieItems()) return base
                val synthetic = Category(
                    id = NEWLY_ADDED_VOD_CATEGORY_ID,
                    name = NEWLY_ADDED_VOD_CATEGORY_NAME,
                    kind = ContentKind.VOD
                )
                if (base.any { it.id == NEWLY_ADDED_VOD_CATEGORY_ID }) base
                else listOf(synthetic) + base
            }
            ContentKind.SERIES -> {
                if (!hasSeriesItems()) return base
                val synthetic = Category(
                    id = NEWLY_ADDED_SERIES_CATEGORY_ID,
                    name = NEWLY_ADDED_VOD_CATEGORY_NAME,
                    kind = ContentKind.SERIES
                )
                if (base.any { it.id == NEWLY_ADDED_SERIES_CATEGORY_ID }) base
                else listOf(synthetic) + base
            }
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
        val movies = cachedItems.filter { item ->
            item.kind == ContentKind.VOD &&
                item.categoryId != NEWLY_ADDED_VOD_CATEGORY_ID
        }
        return sortByRecentlyAdded(movies).take(limit)
    }

    /** Newest series titles for the Series "Newly added" rail. */
    fun newlyAddedSeries(limit: Int = NEWLY_ADDED_LIMIT): List<MediaItem> {
        val series = cachedItems.filter { item ->
            item.kind == ContentKind.SERIES &&
                item.categoryId != NEWLY_ADDED_SERIES_CATEGORY_ID
        }
        return sortByRecentlyAdded(series).take(limit)
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

    fun sortCatalogItems(items: List<MediaItem>, mode: CatalogSort): List<MediaItem> =
        when (mode) {
            CatalogSort.AZ -> items.sortedBy { it.name.lowercase() }
            CatalogSort.ZA -> items.sortedByDescending { it.name.lowercase() }
            CatalogSort.RECENTLY_ADDED -> sortByRecentlyAdded(items)
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
        return liveHits + vodHits
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
        val needsRating = item.displayRating() == null && isVodOrSeries
        if (!needsPoster && !needsTrailer && !needsPlot && !needsRating) return@withContext item
        val sid = item.xtreamStreamId ?: return@withContext item
        val creds = activeXtreamCreds ?: return@withContext item
        // Poster-only fast path when trailer/plot/rating already known
        if (needsPoster && !needsTrailer && !needsPlot && !needsRating) {
            val cached = posterMutex.withLock { posterCache[sid] }
            if (cached != null) {
                return@withContext if (cached.isBlank()) item else item.copy(posterUrl = cached)
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
        out
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
        val cur = cachedItems
        val i = cur.indexOfFirst { it.id == id }
        if (i < 0) return
        val next = cur.toMutableList()
        next[i] = next[i].copy(youtubeTrailer = trailer)
        cachedItems = next
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
                } else if (bulkDeferredForPlayback.getAndSet(false)) {
                    val c = bulkPendingCreds
                    val f = bulkPendingFile
                    if (c != null && f != null && activeXtreamCreds == c &&
                        !GuideBulkCache.isFresh(f, System.currentTimeMillis())
                    ) {
                        Log.i("TotalIPTV.Guide", "bulkDownload resumed after playback")
                        startBulkDownload(c, f)
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
        synchronized(xmltvLock) {
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
        const val NEWLY_ADDED_VOD_CATEGORY_ID = "vod-newly-added"
        const val NEWLY_ADDED_SERIES_CATEGORY_ID = "series-newly-added"
        const val NEWLY_ADDED_VOD_CATEGORY_NAME = "Newly added"
        const val NEWLY_ADDED_LIMIT = 150
    }
}

/** In-memory browse sort for Live / Movies / Series grids. */
enum class CatalogSort {
    AZ,
    ZA,
    RECENTLY_ADDED
}

