package com.totaliptv.pro.data.local

import android.content.Context
import android.util.Log
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.WatchProgress
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Local resume positions for VOD / series episodes.
 * SharedPreferences with commit() so Home refresh sees writes immediately after player exit.
 * Live TV is never stored.
 */
class WatchProgressStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun get(id: String): WatchProgress? =
        all().firstOrNull { it.id == id }

    /**
     * Progress for a catalog poster / detail sheet.
     * Matches leaf id, or catalogId (series parent), or vod/series id equality.
     */
    fun forCatalogItem(itemId: String): WatchProgress? {
        if (itemId.isBlank()) return null
        val list = all().filter { it.shouldResume() }
        list.firstOrNull { it.id == itemId }?.let { return it }
        list.firstOrNull { it.catalogId == itemId }?.let { return it }
        return null
    }

    fun all(): List<WatchProgress> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(WatchProgress.serializer()), raw)
        }.onFailure {
            Log.e(TAG, "decode failed", it)
        }.getOrDefault(emptyList())
    }

    /** In-progress items eligible for resume (≥15s, not completed), newest first. */
    fun continueWatching(limit: Int = 24): List<WatchProgress> {
        val raw = all()
        val list = raw
            .filter { it.kind != ContentKind.LIVE && it.shouldResume() }
            .sortedByDescending { it.updatedAtMs }
            .take(limit)
        if (raw.isNotEmpty() || list.isNotEmpty()) {
            Log.i(TAG, "continueWatching raw=${raw.size} out=${list.size} limit=$limit")
        }
        return list
    }

    /** Map of catalog-facing id → progress for poster badges. */
    fun progressByCatalogId(): Map<String, WatchProgress> {
        val out = LinkedHashMap<String, WatchProgress>()
        for (p in continueWatching(MAX_ENTRIES)) {
            out[p.id] = p
            p.catalogId?.takeIf { it.isNotBlank() }?.let { out.putIfAbsent(it, p) }
        }
        return out
    }

    fun save(progress: WatchProgress) {
        if (progress.kind == ContentKind.LIVE) return
        if (progress.id.isBlank()) return
        if (progress.isCompleted()) {
            clear(progress.id)
            return
        }
        // Align with shouldResume / "watched ≥15s"
        if (progress.positionMs < MIN_SAVE_MS) return
        val next = all().filterNot { it.id == progress.id }.toMutableList()
        next.add(progress.copy(updatedAtMs = System.currentTimeMillis()))
        val trimmed = next.sortedByDescending { it.updatedAtMs }.take(MAX_ENTRIES)
        writeAll(trimmed)
        Log.i(TAG, "saved id=${progress.id} catalog=${progress.catalogId} pos=${progress.positionMs} dur=${progress.durationMs}")
    }

    fun clear(id: String) {
        if (id.isBlank()) return
        val next = all().filterNot { it.id == id || it.catalogId == id }
        writeAll(next)
    }

    /** Clear leaf + any entry mapped to this catalog poster id. */
    fun clearForCatalogItem(itemId: String) {
        if (itemId.isBlank()) return
        val next = all().filterNot {
            it.id == itemId || it.catalogId == itemId
        }
        writeAll(next)
    }

    private fun writeAll(list: List<WatchProgress>) {
        val encoded = json.encodeToString(ListSerializer(WatchProgress.serializer()), list)
        // commit() so Home ON_RESUME / tab switch reliably reads fresh data
        val ok = prefs.edit().putString(KEY, encoded).commit()
        if (!ok) Log.e(TAG, "SharedPreferences commit failed")
    }

    companion object {
        private const val TAG = "TotalIPTV.Progress"
        private const val PREFS = "watch_progress"
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 80
        const val MIN_SAVE_MS = 15_000L
    }
}
