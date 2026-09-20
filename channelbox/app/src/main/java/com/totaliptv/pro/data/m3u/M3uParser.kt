package com.totaliptv.pro.data.m3u

import com.totaliptv.pro.data.LiveChannelMapping
import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import java.util.UUID

object M3uParser {
    data class Result(
        val categories: List<Category>,
        val items: List<MediaItem>
    )

    fun parse(content: String, defaultKind: ContentKind = ContentKind.LIVE): Result {
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val items = mutableListOf<MediaItem>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val meta = line.substringAfter(":", line)
                val name = meta.substringAfterLast(",").trim().ifEmpty { "Channel ${items.size + 1}" }
                val attrs = parseAttributes(line)
                val logo = attrs["tvg-logo"] ?: attrs["logo"]
                val group = attrs["group-title"] ?: attrs["group"] ?: "Uncategorized"
                val idAttr = attrs["tvg-id"] ?: attrs["channel-id"]
                val tvgChno = attrs["tvg-chno"]?.toIntOrNull()?.takeIf { it > 0 }
                val url = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("#") }
                if (url != null) {
                    val kind = if (looksLikeVod(url, group, name)) ContentKind.VOD else defaultKind
                    val catId = "m3u-${kind.name.lowercase()}-${group.hashCode()}"
                    items += MediaItem(
                        id = idAttr?.takeIf { it.isNotBlank() } ?: UUID.nameUUIDFromBytes(url.toByteArray()).toString(),
                        name = name,
                        streamUrl = url,
                        categoryId = catId,
                        kind = kind,
                        logoUrl = logo?.takeIf { it.isNotBlank() },
                        groupTitle = group,
                        epgChannelId = if (kind == ContentKind.LIVE) idAttr?.takeIf { it.isNotBlank() } else null,
                        channelNum = if (kind == ContentKind.LIVE) tvgChno else null,
                        categoryIds = listOf(catId)
                    )
                    i += 2
                    continue
                }
            }
            i++
        }

        val categories = items
            .groupBy { it.categoryId to it.kind }
            .map { (key, list) ->
                Category(
                    id = key.first ?: "unknown",
                    name = list.firstOrNull()?.groupTitle ?: "Uncategorized",
                    kind = key.second
                )
            }
            .sortedBy { it.name.lowercase() }

        val live = LiveChannelMapping.sortLiveChannels(
            LiveChannelMapping.dedupeLiveChannels(items.filter { it.kind == ContentKind.LIVE })
        )
        val rest = items.filter { it.kind != ContentKind.LIVE }
        return Result(categories = categories, items = live + rest)
    }

    private fun parseAttributes(extinf: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val regex = Regex("""([\w-]+)="([^"]*)"""")
        regex.findAll(extinf).forEach { match ->
            result[match.groupValues[1].lowercase()] = match.groupValues[2]
        }
        return result
    }

    private fun looksLikeVod(url: String, group: String, name: String): Boolean {
        val hay = "$url $group $name".lowercase()
        return hay.contains("movie") || hay.contains("vod") || hay.contains(".mp4") || hay.contains(".mkv")
    }
}
