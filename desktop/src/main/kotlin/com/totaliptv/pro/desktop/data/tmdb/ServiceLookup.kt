package com.totaliptv.pro.desktop.data.tmdb

import com.totaliptv.pro.desktop.artwork.ArtworkSettings
import com.totaliptv.pro.desktop.data.PlaybackGate
import com.totaliptv.pro.desktop.util.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads TMDB service / ratings lists, caches them on disk, and resumes after a
 * pause or restart. Never logs the API key or its fingerprint.
 */
object ServiceLookup {
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null
    private val deferredForPlayback = AtomicBoolean(false)
    private var watchingPlayback = false

    private val _index = MutableStateFlow<ServiceIndex?>(null)
    private val _status = MutableStateFlow("")
    private val _revision = MutableStateFlow(0)

    val index: StateFlow<ServiceIndex?> = _index.asStateFlow()
    val status: StateFlow<String> = _status.asStateFlow()
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun cacheFile(): File = AppPaths.configDir.resolve("tmdb-services.json").toFile()

    fun start(force: Boolean = false) {
        ensurePlaybackWatch()
        synchronized(lock) {
            val running = job
            if (running?.isActive == true) {
                if (!force) return
                running.cancel()
            }
            job = scope.launch {
                runCatching { runLookup(force) }
            }
        }
    }

    private fun ensurePlaybackWatch() {
        if (watchingPlayback) return
        watchingPlayback = true
        scope.launch {
            PlaybackGate.active.collect { playing ->
                if (!playing && deferredForPlayback.getAndSet(false)) {
                    start(force = false)
                }
            }
        }
    }

    private fun persist(
        file: File,
        fp: String,
        entries: List<TmdbServiceEntry>,
        progress: List<TmdbQueryCursor>,
        complete: Boolean,
        fetchedAtMs: Long
    ) {
        if (entries.isEmpty()) return
        TmdbServiceLookup.writeCache(
            file,
            TmdbServiceCache(
                fetchedAtMs = fetchedAtMs,
                keyFingerprint = fp,
                entries = entries,
                complete = complete,
                progress = if (complete) emptyList() else progress
            )
        )
    }

    private suspend fun runLookup(force: Boolean) {
        val file = cacheFile()
        val key = ArtworkSettings.tmdbApiKey
        val fp = TmdbServiceLookup.fingerprint(key)
        val usable = TmdbServiceLookup.readUsableCache(file)
        if (usable != null && usable.entries.isNotEmpty() && _index.value == null) {
            applyIndex(usable.entries)
        }
        if (key.isBlank()) {
            _status.value = if (usable != null) {
                "Using saved service lists (no TMDB key set)"
            } else {
                "Add a TMDB API key to fill Netflix / Prime Video / HBO / Max / Apple TV+ / Hulu / Disney+ / Paramount+"
            }
            return
        }
        val now = System.currentTimeMillis()
        val current = TmdbServiceLookup.readCache(file)
        if (!force && TmdbServiceLookup.isFresh(current, fp, now)) {
            _status.value = "Service groups updated " + formatShortDate(current!!.fetchedAtMs) +
                " (${_index.value?.size ?: 0} titles)"
            return
        }
        if (!PlaybackGate.refreshAllowed()) {
            deferredForPlayback.set(true)
            _status.value = if (_index.value != null) {
                "Service groups updated " + formatShortDate(usable?.fetchedAtMs ?: now) +
                    " (${_index.value?.size ?: 0} titles)"
            } else {
                "Looking up streaming services… (paused during playback)"
            }
            return
        }
        _status.value = "Looking up streaming services…"
        val lookup = TmdbServiceLookup(http)
        var lastBumpMs = 0L
        var lastPersistMs = 0L
        val lookupJob = kotlin.coroutines.coroutineContext[Job]
        val startedAt = now
        val seed = usable?.entries.orEmpty()
        val resumeProgress = if (current?.keyFingerprint == fp) current.progress else emptyList()
        val result = try {
            lookup.fetch(
                apiKey = key,
                seed = seed,
                progress = resumeProgress,
                onProgress = { done, total ->
                    _status.value = "Looking up streaming services… $done/$total"
                },
                onBatch = { snapshot ->
                    if (PlaybackGate.refreshAllowed()) {
                        val ts = System.currentTimeMillis()
                        if (lastBumpMs == 0L || ts - lastBumpMs >= 5_000L) {
                            lastBumpMs = ts
                            applyIndex(snapshot)
                        }
                    }
                },
                onPersist = { entries, progress, complete ->
                    if (PlaybackGate.refreshAllowed()) {
                        val ts = System.currentTimeMillis()
                        if (complete || lastPersistMs == 0L || ts - lastPersistMs >= 8_000L) {
                            lastPersistMs = ts
                            persist(file, fp, entries, progress, complete, startedAt)
                        }
                    }
                },
                isActive = { lookupJob?.isActive != false },
                shouldPause = { !PlaybackGate.refreshAllowed() }
            )
        } catch (e: TmdbServiceLookup.LookupFailed) {
            _status.value = e.message ?: "TMDB lookup failed"
            return
        }
        if (result.entries.isEmpty()) {
            _status.value = if (_index.value != null) {
                "Service lookup failed; using saved lists"
            } else {
                "TMDB returned no service lists (check network)"
            }
            return
        }
        persist(file, fp, result.entries, result.progress, result.complete, startedAt)
        if (!result.complete) {
            if (result.paused) {
                deferredForPlayback.set(true)
                _status.value = "Looking up streaming services… (paused during playback)"
            } else {
                _status.value = "Service lookup paused; saved progress (${result.entries.size} titles)"
            }
            if (PlaybackGate.refreshAllowed()) applyIndex(result.entries)
            return
        }
        applyIndex(result.entries)
        _status.value = "Service groups updated " + formatShortDate(startedAt) +
            " (${_index.value?.size ?: 0} titles)"
    }

    private fun applyIndex(entries: List<TmdbServiceEntry>) {
        if (entries.isEmpty()) return
        runCatching {
            _index.value = ServiceIndex(entries)
            _revision.value = _revision.value + 1
        }
    }

    private fun formatShortDate(ms: Long): String =
        SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(ms))

    internal fun resetForTest() {
        synchronized(lock) { job?.cancel(); job = null }
        deferredForPlayback.set(false)
        _index.value = null
        _status.value = ""
        _revision.value = 0
    }
}
