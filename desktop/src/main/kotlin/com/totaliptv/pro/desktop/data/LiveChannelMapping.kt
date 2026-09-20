package com.totaliptv.pro.desktop.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Shared Live TV ↔ TV Guide channel identity, membership, and order.
 *
 * Guide and Live must call [filterLiveChannels] with the same category/query so rows
 * correspond. Playback and EPG stay keyed by [MediaItem.xtreamStreamId], never by
 * list index, [MediaItem.channelNum], or display name.
 */
object LiveChannelMapping {

    data class XtreamLiveIds(
        val streamId: Int,
        val channelNum: Int,
        val epgChannelId: String?,
        val rawCategoryIds: List<String>
    )

    fun liveKey(item: MediaItem): String {
        val sid = item.xtreamStreamId
        return if (sid != null && sid > 0) "sid:$sid" else item.id
    }

    fun belongsToCategory(item: MediaItem, categoryId: String?): Boolean {
        if (categoryId.isNullOrBlank()) return true
        if (item.categoryId == categoryId) return true
        if (item.categoryIds.contains(categoryId)) return true
        return false
    }

    fun matchesQuery(item: MediaItem, query: String): Boolean {
        if (query.isBlank()) return true
        if (item.name.contains(query, ignoreCase = true)) return true
        if (item.groupTitle?.contains(query, ignoreCase = true) == true) return true
        if (item.epgChannelId?.contains(query, ignoreCase = true) == true) return true
        return false
    }

    /**
     * Canonical live list for both Live TV and TV Guide.
     * Dedupes by stream_id, filters by all category memberships, then sorts by `num`.
     */
    fun filterLiveChannels(
        channels: List<MediaItem>,
        categoryId: String? = null,
        query: String = ""
    ): List<MediaItem> {
        val filtered = LinkedHashMap<String, MediaItem>(channels.size)
        for (item in channels) {
            if (item.kind != ContentKind.LIVE) continue
            if (!belongsToCategory(item, categoryId)) continue
            if (!matchesQuery(item, query)) continue
            filtered.putIfAbsent(liveKey(item), item)
        }
        return sortLiveChannels(filtered.values.toList())
    }

    fun dedupeLiveChannels(channels: List<MediaItem>): List<MediaItem> {
        val seen = LinkedHashMap<String, MediaItem>(channels.size)
        for (item in channels) {
            seen.putIfAbsent(liveKey(item), item)
        }
        return seen.values.toList()
    }

    fun sortLiveChannels(channels: List<MediaItem>): List<MediaItem> {
        val hasNums = channels.any { it.channelNum > 0 }
        if (!hasNums) return channels
        return channels.sortedWith(
            compareBy<MediaItem> { ch -> if (ch.channelNum > 0) ch.channelNum else Int.MAX_VALUE }
                .thenBy { it.xtreamStreamId ?: Int.MAX_VALUE }
                .thenBy { it.name.lowercase() }
        )
    }

    fun catalogCategoryIds(rawIds: List<String>, prefix: String = "live"): List<String> =
        rawIds.map { id -> "$prefix-${id.trim()}" }.filter { it.length > prefix.length + 1 }

    /**
     * Parse Xtream live identity fields.
     * [streamIdEl] is `stream_id` only. [fallbackIdEl] (`id`) is used only when stream_id is absent.
     * [numEl] is never used as a stream id.
     */
    fun parseXtreamLiveIds(
        streamIdEl: JsonElement?,
        numEl: JsonElement?,
        epgChannelIdEl: JsonElement?,
        categoryIdEl: JsonElement?,
        categoryIdsEl: JsonElement? = null,
        fallbackIdEl: JsonElement? = null,
        tvgIdEl: JsonElement? = null
    ): XtreamLiveIds {
        val streamId = parsePositiveInt(streamIdEl).takeIf { it > 0 }
            ?: parsePositiveInt(fallbackIdEl)
        return XtreamLiveIds(
            streamId = streamId,
            channelNum = parsePositiveInt(numEl),
            epgChannelId = parseEpgChannelId(epgChannelIdEl) ?: parseEpgChannelId(tvgIdEl),
            rawCategoryIds = parseRawCategoryIds(categoryIdEl, categoryIdsEl)
        )
    }

    fun parsePositiveInt(el: JsonElement?): Int {
        if (el == null || el is JsonNull) return 0
        return when (el) {
            is JsonPrimitive -> {
                el.intOrNull?.takeIf { it > 0 }
                    ?: el.longOrNull?.takeIf { it > 0L && it <= Int.MAX_VALUE }?.toInt()
                    ?: el.contentOrNull?.trim()?.toLongOrNull()
                        ?.takeIf { it > 0L && it <= Int.MAX_VALUE }?.toInt()
                    ?: 0
            }
            else -> 0
        }
    }

    fun parseEpgChannelId(el: JsonElement?): String? {
        if (el == null || el is JsonNull) return null
        val raw = when (el) {
            is JsonPrimitive -> el.contentOrNull?.trim()
            else -> null
        } ?: return null
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) return null
        return raw
    }

    fun parseRawCategoryIds(categoryIdEl: JsonElement?, categoryIdsEl: JsonElement? = null): List<String> {
        val out = LinkedHashSet<String>()
        collectCategoryIds(categoryIdEl, out)
        collectCategoryIds(categoryIdsEl, out)
        return out.toList()
    }

    private fun collectCategoryIds(el: JsonElement?, out: MutableSet<String>) {
        if (el == null || el is JsonNull) return
        when (el) {
            is JsonArray -> el.forEach { collectCategoryIds(it, out) }
            is JsonPrimitive -> {
                val content = el.contentOrNull?.trim().orEmpty()
                if (content.isEmpty() || content.equals("null", ignoreCase = true)) return
                if (content.contains(',') || content.contains(';') || content.contains('|')) {
                    content.split(',', ';', '|').forEach { part ->
                        val id = part.trim()
                        if (id.isNotEmpty() && !id.equals("null", ignoreCase = true)) out += id
                    }
                } else {
                    out += content
                }
            }
            else -> Unit
        }
    }
}
