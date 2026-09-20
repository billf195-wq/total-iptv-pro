package com.totaliptv.pro.ui.epg

import com.totaliptv.pro.data.model.EpgChannelRow

/**
 * Desktop loads EPG per selected/visible channel ([needEpg]). Android TV used
 * to `awaitAll()` every `get_short_epg` in the category before painting blocks.
 *
 * 1.4.53 tried a Channel + [Dispatchers.IO] collector gated on Compose
 * `guideLoadGen`; snapshot reads / child failure cancelled the whole scope so
 * **no blocks ever applied**. Launch on Main, fetch on IO, apply on Main.
 */
object GuideEpgLoad {
    /** In-flight cap; CatalogRepository semaphore matches this. */
    const val PARALLEL = 10

    /** Visible rows plus a small prefetch so DPAD-down is already filled. */
    const val VISIBLE_PREFETCH = 20

    fun fetchOrder(
        channelIds: List<String>,
        firstVisibleIndex: Int,
        focusedId: String?,
        alreadyStarted: Set<String>
    ): List<Int> {
        if (channelIds.isEmpty()) return emptyList()
        val last = channelIds.lastIndex
        val start = firstVisibleIndex.coerceIn(0, last)
        val focusedIdx = focusedId?.let { id -> channelIds.indexOf(id) }?.takeIf { it >= 0 }
        val visibleEnd = (start + VISIBLE_PREFETCH).coerceAtMost(channelIds.size)
        return buildList {
            focusedIdx?.let { add(it) }
            addAll(start until visibleEnd)
            addAll(channelIds.indices)
        }.distinct().filter { idx -> channelIds[idx] !in alreadyStarted }
    }

    fun nextBatch(
        channelIds: List<String>,
        firstVisibleIndex: Int,
        focusedId: String?,
        alreadyStarted: Set<String>,
        limit: Int = PARALLEL
    ): List<Int> = fetchOrder(channelIds, firstVisibleIndex, focusedId, alreadyStarted).take(limit)

    fun applyRow(rows: List<EpgChannelRow>, filled: EpgChannelRow): List<EpgChannelRow> =
        rows.map { if (it.channel.id == filled.channel.id) filled else it }
}
