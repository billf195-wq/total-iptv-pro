package com.totaliptv.pro.ui.home

import android.content.Context
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.local.ContinueWatchingOrder
import com.totaliptv.pro.data.local.WatchProgressStore
import com.totaliptv.pro.data.model.WatchProgress

/** Long-press menu on a Continue watching title (OK hold on the remote, long tap on phone). */
enum class ContinueWatchingAction(val label: String) {
    REMOVE("Remove from Continue watching"),
    MOVE_FRONT("Move to front"),
    MOVE_LEFT("Move left"),
    MOVE_RIGHT("Move right"),
    MARK_WATCHED("Mark as watched"),
    TOGGLE_FAVORITE("Favorite");

    companion object {
        /** Actions that make sense for the title at [index] in a row of [size]. */
        fun forPosition(index: Int, size: Int, includeFavorite: Boolean): List<ContinueWatchingAction> {
            val out = mutableListOf(REMOVE)
            if (index > 0) {
                out.add(MOVE_FRONT)
                out.add(MOVE_LEFT)
            }
            if (index in 0 until size - 1) out.add(MOVE_RIGHT)
            out.add(MARK_WATCHED)
            if (includeFavorite) out.add(TOGGLE_FAVORITE)
            return out
        }

        /**
         * Item that gets focus when the menu opens: never Remove.
         * Move to front when offered, otherwise Mark as watched; null means focus Cancel.
         */
        fun initialFocus(actions: List<ContinueWatchingAction>): ContinueWatchingAction? =
            listOf(MOVE_FRONT, MARK_WATCHED).firstOrNull { it in actions }
    }
}

object ContinueWatchingActions {
    private fun store(context: Context): WatchProgressStore =
        (context.applicationContext as? TotalIptvProApp)?.watchProgress
            ?: WatchProgressStore(context.applicationContext)

    /**
     * Applies a row action and returns a short message for a toast, or null when nothing changed.
     * TOGGLE_FAVORITE is handled by the caller (it lives in the catalog repository).
     */
    fun apply(context: Context, entry: WatchProgress, action: ContinueWatchingAction): String? {
        val store = store(context)
        return when (action) {
            ContinueWatchingAction.REMOVE -> {
                store.hideFromContinueWatching(entry.id)
                "Removed from Continue watching"
            }
            ContinueWatchingAction.MOVE_FRONT ->
                if (store.moveInContinueWatching(entry.id, ContinueWatchingOrder.Move.FRONT)) "Moved to front" else null
            ContinueWatchingAction.MOVE_LEFT ->
                if (store.moveInContinueWatching(entry.id, ContinueWatchingOrder.Move.LEFT)) "Moved left" else null
            ContinueWatchingAction.MOVE_RIGHT ->
                if (store.moveInContinueWatching(entry.id, ContinueWatchingOrder.Move.RIGHT)) "Moved right" else null
            ContinueWatchingAction.MARK_WATCHED -> {
                WatchedActions.setWatched(context, entry.id, true)
                "Marked as watched"
            }
            ContinueWatchingAction.TOGGLE_FAVORITE -> null
        }
    }
}

/** Mark as watched / unwatched for movies and episodes, shared by TV and phone. */
object WatchedActions {
    fun isWatched(context: Context, id: String): Boolean =
        runCatching {
            (context.applicationContext as? TotalIptvProApp)?.watched?.isWatched(id) == true
        }.getOrDefault(false)

    /** Watched also drops the title from Continue watching; unwatched only clears the check. */
    fun setWatched(context: Context, id: String, watched: Boolean) {
        if (id.isBlank()) return
        val app = context.applicationContext as? TotalIptvProApp ?: return
        runCatching { app.watched.setWatched(id, watched) }
        if (watched) runCatching { app.watchProgress.clear(id) }
    }
}
