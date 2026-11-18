package com.totaliptv.pro.data.local

import com.totaliptv.pro.data.lock.ParentalGate
import android.content.Context
import android.util.Log
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.WatchProgress
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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

    /**
     * In-progress items eligible for resume (≥15s, not completed), newest first. No home-row cap.
     * Titles the viewer removed from the row stay hidden until they are played again, and a
     * manual order (Move to front / left / right) holds until a title is played again.
     */
    fun continueWatching(limit: Int = Int.MAX_VALUE): List<WatchProgress> {
        val raw = all()
        val list = ContinueWatchingOrder.arrange(
            raw.filter { it.kind != ContentKind.LIVE && it.shouldResume() && !ParentalGate.hides(it) },
            hiddenMarks(),
            orderMarks()
        ).take(limit)
        if (raw.isNotEmpty() || list.isNotEmpty()) {
            Log.i(TAG, "continueWatching raw=${raw.size} out=${list.size} limit=$limit")
        }
        return list
    }

    /** Map of catalog-facing id → progress for poster badges. */
    fun progressByCatalogId(): Map<String, WatchProgress> {
        val out = LinkedHashMap<String, WatchProgress>()
        // Includes titles removed from the Continue watching row so posters keep their % badge.
        val resumable = all()
            .filter { it.kind != ContentKind.LIVE && it.shouldResume() }
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_ENTRIES)
        for (p in resumable) {
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

    /** Drop Continue watching rows for a deleted recording (id, rec-id, or file path). */
    fun clearRecording(recordingId: String, filePath: String?) {
        if (recordingId.isBlank() && filePath.isNullOrBlank()) return
        val recId = "rec-$recordingId"
        val path = filePath?.trim().orEmpty()
        val next = all().filterNot {
            it.id == recId ||
                it.id == recordingId ||
                (path.isNotEmpty() && it.streamUrl == path)
        }
        writeAll(next)
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

    /** Hide a title from the Continue watching row. Resume still works; playing it again brings it back. */
    fun hideFromContinueWatching(id: String) {
        val entry = all().firstOrNull { it.id == id } ?: return
        val marks = hiddenMarks().toMutableMap()
        marks[id] = entry.updatedAtMs
        writeMarks(KEY_HIDDEN, marks)
        Log.i(TAG, "cw hide id=$id")
    }

    /** Move a Continue watching title. Returns false when the move does nothing. */
    fun moveInContinueWatching(id: String, move: ContinueWatchingOrder.Move): Boolean {
        val current = continueWatching()
        val reordered = ContinueWatchingOrder.move(current.map { it.id }, id, move) ?: return false
        val byId = current.associateBy { it.id }
        val top = current.maxOfOrNull { ContinueWatchingOrder.effectiveKey(it, orderMarks()) } ?: 0L
        val marks = LinkedHashMap<String, Long>()
        val stamps = LinkedHashMap<String, Long>()
        reordered.forEachIndexed { index, itemId ->
            val entry = byId[itemId] ?: return@forEachIndexed
            marks[itemId] = top - index
            stamps[itemId] = entry.updatedAtMs
        }
        writeMarks(KEY_ORDER, marks)
        writeMarks(KEY_ORDER_STAMP, stamps)
        Log.i(TAG, "cw move id=$id move=$move")
        return true
    }

    private fun hiddenMarks(): Map<String, Long> = readMarks(KEY_HIDDEN)

    private fun orderMarks(): Map<String, ContinueWatchingOrder.Mark> {
        val keys = readMarks(KEY_ORDER)
        val stamps = readMarks(KEY_ORDER_STAMP)
        return keys.mapNotNull { (id, key) ->
            val stamp = stamps[id] ?: return@mapNotNull null
            id to ContinueWatchingOrder.Mark(key, stamp)
        }.toMap()
    }

    private fun readMarks(key: String): Map<String, Long> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), raw)
        }.getOrDefault(emptyMap())
    }

    private fun writeMarks(key: String, marks: Map<String, Long>) {
        val encoded = json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), marks)
        val ok = prefs.edit().putString(key, encoded).commit()
        if (!ok) Log.e(TAG, "SharedPreferences commit failed ($key)")
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
        private const val KEY_HIDDEN = "cw_hidden"
        private const val KEY_ORDER = "cw_order"
        private const val KEY_ORDER_STAMP = "cw_order_stamp"
        private const val MAX_ENTRIES = 500
        const val MIN_SAVE_MS = 15_000L
    }
}
