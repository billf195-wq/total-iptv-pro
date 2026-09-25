package com.totaliptv.pro.data.m3u

import com.totaliptv.pro.data.LiveChannelMapping
import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import java.io.BufferedReader
import java.io.StringReader
import java.util.UUID

object M3uParser {
    data class Result(
        val categories: List<Category>,
        val items: List<MediaItem>,
        val xmltvUrl: String? = null
    )

    fun parse(content: String, defaultKind: ContentKind = ContentKind.LIVE): Result =
        BufferedReader(StringReader(content)).use { parse(it, defaultKind) }

    fun parse(reader: BufferedReader, defaultKind: ContentKind = ContentKind.LIVE): Result {
        val items = mutableListOf<MediaItem>()
        var xmltvUrl: String? = null
        var pendingExtInf: ExtInfData? = null
        val attrRegex = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")

        reader.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachLine

            if (line.startsWith("#EXTM3U", ignoreCase = true)) {
                attrRegex.findAll(line).forEach { match ->
                    val key = match.groupValues[1].lowercase()
                    val value = match.groupValues[2].trim()
                    if ((key == "url-tvg" || key == "x-tvg-url") && value.isNotBlank() && xmltvUrl == null) {
                        xmltvUrl = value
                    }
                }
                return@forEachLine
            }

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val meta = line.substringAfter(":", line)
                val name = meta.substringAfterLast(",").trim().ifEmpty { "Channel ${items.size + 1}" }
                var group = "Uncategorized"
                var logo: String? = null
                var idAttr: String? = null
                var tvgChno: Int? = null
                var typeAttr: String? = null

                attrRegex.findAll(line).forEach { match ->
                    val key = match.groupValues[1].lowercase()
                    val value = match.groupValues[2].trim()
                    when (key) {
                        "group-title", "group" -> if (value.isNotBlank()) group = value
                        "tvg-logo", "logo" -> if (value.isNotBlank()) logo = value
                        "tvg-id", "channel-id" -> if (value.isNotBlank()) idAttr = value
                        "tvg-chno" -> tvgChno = value.toIntOrNull()?.takeIf { it > 0 }
                        "type", "tvg-type" -> if (value.isNotBlank()) typeAttr = value
                    }
                }

                pendingExtInf = ExtInfData(
                    name = name,
                    group = group,
                    logo = logo,
                    idAttr = idAttr,
                    tvgChno = tvgChno,
                    typeAttr = typeAttr
                )
                return@forEachLine
            }

            if (line.startsWith("#") || pendingExtInf == null) return@forEachLine
            val extInf = pendingExtInf!!
            pendingExtInf = null
            val url = line
            val kind = when {
                M3uKind.isSeries(url, extInf.group, extInf.typeAttr) -> ContentKind.SERIES
                M3uKind.isVod(url, extInf.group, extInf.name) -> ContentKind.VOD
                else -> defaultKind
            }
            val catId = "m3u-${kind.name.lowercase()}-${extInf.group.hashCode()}"
            items += MediaItem(
                id = extInf.idAttr?.takeIf { it.isNotBlank() } ?: UUID.nameUUIDFromBytes(url.toByteArray()).toString(),
                name = extInf.name,
                streamUrl = url,
                categoryId = catId,
                kind = kind,
                logoUrl = extInf.logo?.takeIf { it.isNotBlank() },
                groupTitle = extInf.group,
                epgChannelId = if (kind == ContentKind.LIVE) extInf.idAttr?.takeIf { it.isNotBlank() } else null,
                channelNum = if (kind == ContentKind.LIVE) extInf.tvgChno else null,
                categoryIds = listOf(catId)
            )
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
        return Result(categories = categories, items = live + rest, xmltvUrl = xmltvUrl)
    }

    private data class ExtInfData(
        val name: String,
        val group: String,
        val logo: String?,
        val idAttr: String?,
        val tvgChno: Int?,
        val typeAttr: String?
    )
}
