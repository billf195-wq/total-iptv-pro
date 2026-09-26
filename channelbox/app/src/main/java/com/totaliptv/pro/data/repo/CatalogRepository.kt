package com.totaliptv.pro.data.repo

import android.util.Log
import com.totaliptv.pro.data.LiveChannelMapping
import com.totaliptv.pro.data.LiveEpgBinding
import com.totaliptv.pro.data.local.AppPreferences
import com.totaliptv.pro.data.m3u.M3uParser
import com.totaliptv.pro.data.m3u.XmltvParser
import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.EpgChannelRow
import com.totaliptv.pro.data.model.EpgNowNext
import com.totaliptv.pro.data.model.EpgProgram
import com.totaliptv.pro.ui.epg.GuideWindow
import com.totaliptv.pro.data.model.FavoriteRef
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.model.PlaylistSource
import com.totaliptv.pro.data.model.SourceType
import com.totaliptv.pro.data.xtream.XtreamApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    /** Matches [com.totaliptv.pro.ui.epg.GuideEpgLoad.PARALLEL]; do not storm get_short_epg. */
    private val epgFetchSemaphore = Semaphore(10)

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
            epgCache.clear()
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
        epgCache.clear()
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
                                epgCache.clear()
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
                            val detail = t.message ?: t.javaClass.simpleName
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
                val detail = t.message ?: t.javaClass.simpleName
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
                val detail = t.message ?: t.javaClass.simpleName
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

    private fun toSeriesEpisodeItem(item: MediaItem, resolved: XtreamApi.SeriesEpisode): MediaItem =
        item.copy(
            id = "series-ep-${resolved.episodeId}",
            streamUrl = resolved.url,
            name = item.name + " — S${resolved.season}E${resolved.episodeNum} ${resolved.title}".trim()
        )

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
        val cached = epgCache[sid] ?: return null
        val bound = LiveEpgBinding.bindForDisplay(channel, cached, liveSiblings())
        return EpgChannelRow(
            channel = channel,
            programs = bound,
            nowNext = xtreamApi.nowNextFromPrograms(bound)
        )
    }

    /** One channel’s EPG (cached or fetch). Caller updates that row; do not awaitAll the category. */
    suspend fun loadGuideRow(channel: MediaItem): EpgChannelRow = withContext(Dispatchers.IO) {
        epgFetchSemaphore.withPermit {
            val programs = loadPrograms(channel)
            EpgChannelRow(
                channel = channel,
                programs = programs,
                nowNext = xtreamApi.nowNextFromPrograms(programs)
            )
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
        epgCache[sid]?.let { cached ->
            val bound = LiveEpgBinding.bindForDisplay(item, cached, liveSiblings())
            Log.d(
                "TotalIPTV.Guide",
                "epgCacheHit name=${item.name} id=${item.id} sid=$sid num=${item.channelNum} programs=${bound.size} first=${bound.firstOrNull()?.title}"
            )
            return GuideWindow.retain(bound, horizonStart, horizonEnd)
        }
        val short = xtreamApi.fetchShortEpg(creds, sid, limit = GuideWindow.LISTING_LIMIT)
        val programs = if (short.isNotEmpty()) short else xtreamApi.fetchSimpleEpgTable(creds, sid)
        // Cache only the guide horizon. A simple-data-table fallback can be a full day.
        val kept = GuideWindow.retain(programs, horizonStart, horizonEnd)
        val bound = LiveEpgBinding.bindForDisplay(item, kept, liveSiblings())
        Log.i(
            "TotalIPTV.Guide",
            "epgBind name=${item.name} id=${item.id} sid=$sid num=${item.channelNum} epgCh=${item.epgChannelId} programs=${bound.size} first=${bound.firstOrNull()?.title}"
        )
        if (kept.isNotEmpty()) {
            // Cache raw listings so bind can re-run with current nowMs / siblings.
            epgCache[sid] = kept
        }
        return bound
    }

    private fun liveSiblings(): List<MediaItem> =
        cachedItems.filter { it.kind == ContentKind.LIVE }

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
                xmltvByChannel.putAll(XmltvParser.loadFromUrl(url))
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

