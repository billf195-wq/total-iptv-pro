package com.totaliptv.pro.desktop.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object M3uParser {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun loadFromUrl(url: String): Catalog {
        val request = Request.Builder()
            .url(url.trim())
            .header("User-Agent", "TotalIPTVPro-Desktop/1.0")
            .get()
            .build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("M3U download failed: HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        return parse(body)
    }

    fun parse(text: String): Catalog {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val live = mutableListOf<MediaItem>()
        val vod = mutableListOf<MediaItem>()
        val series = mutableListOf<MediaItem>()
        val liveCatNames = linkedSetOf<String>()
        val vodCatNames = linkedSetOf<String>()
        val seriesCatNames = linkedSetOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val name = line.substringAfter(",").trim().ifBlank { "Stream" }
                val group = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val logo = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                val tvgId = Regex("""tvg-id="([^"]*)"""", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                val tvgChno = Regex("""tvg-chno="([^"]*)"""", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                    ?: 0
                val url = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("#") }
                if (url != null) {
                    val lowerGroup = group.lowercase()
                    val isSeries = lowerGroup.contains("series") || lowerGroup.contains("show") ||
                        url.contains("/series/", ignoreCase = true)
                    val isVod = !isSeries && (
                        lowerGroup.contains("movie") || lowerGroup.contains("vod") ||
                            lowerGroup.contains("film") || url.contains("/movie/", ignoreCase = true)
                        )
                    val kind = when {
                        isSeries -> ContentKind.SERIES
                        isVod -> ContentKind.VOD
                        else -> ContentKind.LIVE
                    }
                    val catName = group.ifBlank {
                        when (kind) {
                            ContentKind.SERIES -> "Series"
                            ContentKind.VOD -> "Movies"
                            ContentKind.LIVE -> "Live"
                        }
                    }
                    val catId = "${kind.name.lowercase()}-$catName"
                    when (kind) {
                        ContentKind.SERIES -> seriesCatNames += catName
                        ContentKind.VOD -> vodCatNames += catName
                        ContentKind.LIVE -> liveCatNames += catName
                    }
                    val item = MediaItem(
                        id = "${kind.name.lowercase()}-${url.hashCode()}",
                        name = name,
                        streamUrl = url,
                        categoryId = catId,
                        kind = kind,
                        logoUrl = logo,
                        posterUrl = logo,
                        groupTitle = catName,
                        playable = true,
                        channelNum = if (kind == ContentKind.LIVE) tvgChno else 0,
                        epgChannelId = if (kind == ContentKind.LIVE) tvgId else null,
                        categoryIds = listOf(catId)
                    )
                    when (kind) {
                        ContentKind.SERIES -> series += item
                        ContentKind.VOD -> vod += item
                        ContentKind.LIVE -> live += item
                    }
                    i += 2
                    continue
                }
            }
            i++
        }
        val liveCategories = liveCatNames.map { Category("live-$it", it, ContentKind.LIVE) }
        val vodCategories = vodCatNames.map { Category("vod-$it", it, ContentKind.VOD) }
        val seriesCategories = seriesCatNames.map { Category("series-$it", it, ContentKind.SERIES) }
        if (live.isEmpty() && vod.isEmpty() && series.isEmpty()) error("No streams found in M3U playlist.")
        return Catalog(
            liveCategories = liveCategories,
            vodCategories = vodCategories,
            seriesCategories = seriesCategories,
            liveItems = LiveChannelMapping.sortLiveChannels(LiveChannelMapping.dedupeLiveChannels(live)),
            vodItems = vod,
            seriesItems = series
        )
    }
}
