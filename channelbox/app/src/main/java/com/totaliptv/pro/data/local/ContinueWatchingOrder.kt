package com.totaliptv.pro.data.local

import com.totaliptv.pro.data.model.WatchProgress

/**
 * Pure ordering rules for the Continue watching row.
 *
 * A hide mark or a manual order mark only applies while the entry's updatedAtMs still matches
 * the value saved with the mark. Playing a title again saves a newer updatedAtMs, so it returns
 * to the row (if hidden) and jumps to the front as before.
 */
object ContinueWatchingOrder {
    data class Mark(val key: Long, val stampUpdatedAtMs: Long)

    enum class Move { FRONT, LEFT, RIGHT }

    fun isHidden(entry: WatchProgress, hidden: Map<String, Long>): Boolean =
        hidden[entry.id]?.let { it == entry.updatedAtMs } == true

    fun effectiveKey(entry: WatchProgress, order: Map<String, Mark>): Long {
        val mark = order[entry.id]
        return if (mark != null && mark.stampUpdatedAtMs == entry.updatedAtMs) mark.key else entry.updatedAtMs
    }

    fun arrange(
        entries: List<WatchProgress>,
        hidden: Map<String, Long>,
        order: Map<String, Mark>
    ): List<WatchProgress> =
        entries
            .filterNot { isHidden(it, hidden) }
            .sortedWith(
                compareByDescending<WatchProgress> { effectiveKey(it, order) }
                    .thenByDescending { it.updatedAtMs }
            )

    /** New id order after a move, or null when nothing changes. */
    fun move(ids: List<String>, id: String, move: Move): List<String>? {
        val index = ids.indexOf(id)
        if (index < 0) return null
        val target = when (move) {
            Move.FRONT -> 0
            Move.LEFT -> index - 1
            Move.RIGHT -> index + 1
        }
        if (target < 0 || target >= ids.size || target == index) return null
        val out = ids.toMutableList()
        out.removeAt(index)
        out.add(target, id)
        return out
    }
}
