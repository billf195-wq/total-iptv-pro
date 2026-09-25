package com.totaliptv.pro.desktop.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.TimeUnit

object M3uParser {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val attrRegex = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")

    fun loadFromUrl(url: String): Catalog {
        val request = Request.Builder()
            .url(url.trim())
            .header("User-Agent", "TotalIPTVPro-Desktop/1.0")
            .get()
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("M3U download failed: HTTP ${resp.code}")
            val body = resp.body ?: error("Empty M3U response")
            body.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
                parse(reader)
            }
        }
    }

    fun parse(text: String): Catalog =
        BufferedReader(StringReader(text)).use { parse(it) }

    fun parse(reader: BufferedReader): Catalog {
        val live = mutableListOf<MediaItem>()
        val vod = mutableListOf<MediaItem>()
        val series = mutableListOf<MediaItem>()
        val liveCatNames = linkedSetOf<String>()
        val vodCatNames = linkedSetOf<String>()
        val seriesCatNames = linkedSetOf<String>()
        var xmltvUrl: String? = null
        var pending: ExtInfData? = null

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
                val name = line.substringAfter(",").trim().ifBlank { "Stream" }
                var group = ""
                var logo: String? = null
                var tvgId: String? = null
                var tvgChno = 0
                var typeAttr: String? = null
                attrRegex.findAll(line).forEach { match ->
                    val key = match.groupValues[1].lowercase()
                    val value = match.groupValues[2].trim()
                    when (key) {
                        "group-title", "group" -> if (value.isNotBlank()) group = value
                        "tvg-logo", "logo" -> if (value.isNotBlank()) logo = value
                        "tvg-id", "channel-id" -> if (value.isNotBlank()) tvgId = value
                        "tvg-chno" -> tvgChno = value.toIntOrNull()?.takeIf { it > 0 } ?: 0
                        "type", "tvg-type" -> if (value.isNotBlank()) typeAttr = value
                    }
                }
                pending = ExtInfData(name, group, logo, tvgId, tvgChno, typeAttr)
                return@forEachLine
            }
            if (line.startsWith("#") || pending == null) return@forEachLine
            val ext = pending!!
            pending = null
            val url = line
            val seriesHit = M3uKind.isSeries(url, ext.group, ext.typeAttr)
            val vodHit = !seriesHit && M3uKind.isVod(url, ext.group)
            val kind = when {
                seriesHit -> ContentKind.SERIES
                vodHit -> ContentKind.VOD
                else -> ContentKind.LIVE
            }
            val catName = ext.group.ifBlank {
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
                name = ext.name,
                streamUrl = url,
                categoryId = catId,
                kind = kind,
                logoUrl = ext.logo,
                posterUrl = ext.logo,
                groupTitle = catName,
                playable = true,
                channelNum = if (kind == ContentKind.LIVE) ext.tvgChno else 0,
                epgChannelId = if (kind == ContentKind.LIVE) ext.tvgId else null,
                categoryIds = listOf(catId)
            )
            when (kind) {
                ContentKind.SERIES -> series += item
                ContentKind.VOD -> vod += item
                ContentKind.LIVE -> live += item
            }
        }

        if (live.isEmpty() && vod.isEmpty() && series.isEmpty()) error("No streams found in M3U playlist.")
        return Catalog(
            liveCategories = liveCatNames.map { Category("live-$it", it, ContentKind.LIVE) },
            vodCategories = vodCatNames.map { Category("vod-$it", it, ContentKind.VOD) },
            seriesCategories = seriesCatNames.map { Category("series-$it", it, ContentKind.SERIES) },
            liveItems = LiveChannelMapping.sortLiveChannels(LiveChannelMapping.dedupeLiveChannels(live)),
            vodItems = vod,
            seriesItems = series,
            xmltvUrl = xmltvUrl
        )
    }

    private data class ExtInfData(
        val name: String,
        val group: String,
        val logo: String?,
        val tvgId: String?,
        val tvgChno: Int,
        val typeAttr: String?
    )
}
