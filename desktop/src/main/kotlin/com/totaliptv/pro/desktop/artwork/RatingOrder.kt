package com.totaliptv.pro.desktop.artwork

import com.totaliptv.pro.desktop.data.MediaItem
import com.totaliptv.pro.desktop.player.PlaybackDebugLog
import java.util.Comparator
import java.util.Locale

/**
 * Sort keys copied before TimSort runs. Comparators read only these fields,
 * so a TMDB result landing mid-sort cannot change the ordering contract.
 */
data class RatingSortKey(
    val rating: Double,
    val voteEligible: Boolean,
    val title: String,
    val id: String
)

object RatingOrder {
    /** Quiet period after the last rating batch before Home re-sorts. */
    const val SORT_QUIET_MS = 500L

    fun key(item: MediaItem, rawRank: Double): RatingSortKey {
        val rating = if (rawRank.isFinite()) rawRank else 0.0
        return RatingSortKey(
            rating = rating,
            voteEligible = rating > 0.0,
            title = item.name.lowercase(Locale.ROOT),
            id = item.id
        )
    }

    /**
     * Eligible titles first, then rating (Double.compare, high to low), then title, then id.
     * Nulls sort last. No subtraction.
     */
    private val keyOrder: Comparator<RatingSortKey> =
        Comparator.comparing(
            { key: RatingSortKey -> key.voteEligible },
            Comparator.nullsLast(Comparator.reverseOrder())
        ).thenComparing(
            { key: RatingSortKey -> key.rating },
            Comparator.nullsLast(Comparator.reverseOrder())
        ).thenComparing(
            { key: RatingSortKey -> key.title },
            Comparator.nullsLast(Comparator.naturalOrder())
        ).thenComparing(
            { key: RatingSortKey -> key.id },
            Comparator.nullsLast(Comparator.naturalOrder())
        )

    fun sortByRating(items: List<MediaItem>, rank: (MediaItem) -> Double): List<MediaItem> {
        val keyed = ArrayList<Pair<MediaItem, RatingSortKey>>(items.size)
        for (item in items) {
            val raw = try {
                rank(item)
            } catch (t: Exception) {
                logFailure(t)
                0.0
            }
            keyed.add(item to key(item, raw))
        }
        return sortKeyed(keyed)
    }

    fun sortKeyed(keyed: List<Pair<MediaItem, RatingSortKey>>): List<MediaItem> {
        if (keyed.size <= 1) return keyed.map { it.first }
        return try {
            val copy = keyed.toTypedArray()
            copy.sortWith { left, right -> keyOrder.compare(left.second, right.second) }
            copy.map { it.first }
        } catch (t: Exception) {
            logFailure(t)
            keyed.map { it.first }
        }
    }

    fun sortByTitle(items: List<MediaItem>, descending: Boolean): List<MediaItem> {
        val keyed = items.map { item ->
            item to TitleKey(item.name.lowercase(Locale.ROOT), item.id)
        }
        val order = titleOrder(descending)
        return try {
            val copy = keyed.toTypedArray()
            copy.sortWith { left, right -> order.compare(left.second, right.second) }
            copy.map { it.first }
        } catch (t: Exception) {
            logFailure(t)
            items
        }
    }

    fun sortRecent(items: List<MediaItem>): List<MediaItem> {
        val keyed = items.map { item ->
            item to RecentKey(
                addedEpoch = item.addedEpoch,
                streamId = item.xtreamStreamId ?: 0,
                title = item.name.lowercase(Locale.ROOT),
                id = item.id
            )
        }
        return try {
            val copy = keyed.toTypedArray()
            copy.sortWith { left, right -> recentOrder.compare(left.second, right.second) }
            copy.map { it.first }
        } catch (t: Exception) {
            logFailure(t)
            items
        }
    }

    fun logFailure(t: Throwable) {
        PlaybackDebugLog.note("ratings sort failed: ${t.javaClass.simpleName}: ${t.message}")
    }

    private data class TitleKey(val title: String, val id: String)

    private fun titleOrder(descending: Boolean): Comparator<TitleKey> {
        val titleCmp: Comparator<String> = if (descending) {
            Comparator.nullsLast(Comparator.reverseOrder())
        } else {
            Comparator.nullsLast(Comparator.naturalOrder())
        }
        return Comparator.comparing({ key: TitleKey -> key.title }, titleCmp)
            .thenComparing({ key: TitleKey -> key.id }, Comparator.nullsLast(Comparator.naturalOrder()))
    }

    private data class RecentKey(
        val addedEpoch: Long,
        val streamId: Int,
        val title: String,
        val id: String
    )

    private val recentOrder: Comparator<RecentKey> =
        Comparator.comparingLong(RecentKey::addedEpoch).reversed()
            .thenComparing(
                { key: RecentKey -> key.streamId },
                Comparator.nullsLast(Comparator.reverseOrder())
            ).thenComparing(
                { key: RecentKey -> key.title },
                Comparator.nullsLast(Comparator.naturalOrder())
            ).thenComparing(
                { key: RecentKey -> key.id },
                Comparator.nullsLast(Comparator.naturalOrder())
            )
}
