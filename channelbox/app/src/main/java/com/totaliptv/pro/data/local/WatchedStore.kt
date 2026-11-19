package com.totaliptv.pro.data.local

import android.content.Context
import android.util.Log

/**
 * Movies and episodes the viewer marked as watched (or finished).
 * Keys are leaf ids: the movie id, or series-ep-<episodeId> for an episode.
 */
class WatchedStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isWatched(id: String): Boolean =
        id.isNotBlank() && ids().contains(id)

    fun ids(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun setWatched(id: String, watched: Boolean) {
        if (id.isBlank()) return
        val next = ids().toMutableSet()
        val changed = if (watched) next.add(id) else next.remove(id)
        if (!changed) return
        val ok = prefs.edit().putStringSet(KEY, next).commit()
        if (!ok) Log.e(TAG, "SharedPreferences commit failed")
        Log.i(TAG, "watched id=$id value=$watched")
    }

    companion object {
        private const val TAG = "TotalIPTV.Watched"
        private const val PREFS = "watched_items"
        private const val KEY = "ids"

        /** Leaf id used for an episode row in a detail sheet. */
        fun episodeId(episodeId: Int): String = "series-ep-$episodeId"
    }
}
