package com.totaliptv.pro.artwork

import com.totaliptv.pro.data.model.MediaItem

/**
 * Total orders for catalog rows. [sortByRank] freezes scores before TimSort runs.
 * The comparator never calls back into the rating store.
 */
object MediaOrder {
    fun sortByRank(items: List<MediaItem>, scores: Map<String, Double?>): List<MediaItem> {
        val frozen = HashMap<String, Double?>(items.size)
        for (item in items) frozen[item.id] = scores[item.id]
        return items.sortedWith(byRankDescending(frozen))
    }

    fun byRankDescending(scores: Map<String, Double?>): Comparator<MediaItem> {
        return Comparator { left, right ->
            val byScore = compareScoreDescending(scores[left.id], scores[right.id])
            if (byScore != 0) return@Comparator byScore
            val byTitle = left.name.compareTo(right.name, ignoreCase = true)
            if (byTitle != 0) return@Comparator byTitle
            left.id.compareTo(right.id)
        }
    }

    /** Higher scores first. A missing score sorts after every real score. */
    fun compareScoreDescending(left: Double?, right: Double?): Int {
        if (left == null && right == null) return 0
        if (left == null) return 1
        if (right == null) return -1
        return java.lang.Double.compare(right, left)
    }

    fun byName(descending: Boolean): Comparator<MediaItem> {
        return Comparator { left, right ->
        val byTitle = left.name.compareTo(right.name, ignoreCase = true)
        val directed = if (descending) -byTitle else byTitle
        if (directed != 0) return@Comparator directed
        val byId = left.id.compareTo(right.id)
        if (descending) -byId else byId
        }
    }

    fun byAddedDescending(): Comparator<MediaItem> {
        return Comparator { left, right ->
            val byAdded = java.lang.Long.compare(right.addedMs ?: 0L, left.addedMs ?: 0L)
            if (byAdded != 0) return@Comparator byAdded
            val byStream = (right.xtreamStreamId ?: 0).compareTo(left.xtreamStreamId ?: 0)
            if (byStream != 0) return@Comparator byStream
            val byTitle = left.name.compareTo(right.name, ignoreCase = true)
            if (byTitle != 0) return@Comparator byTitle
            left.id.compareTo(right.id)
        }
    }
}
