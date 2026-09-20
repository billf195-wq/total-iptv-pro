package com.totaliptv.pro.desktop.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.TimeUnit

class XtreamApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
) {
    /** From `server_info.timezone` / `time_now` on the last successful login probe. */
    @Volatile
    internal var providerZone: ZoneId? = null
    @Serializable
    data class XtreamCategory(
        @SerialName("category_id") val categoryId: String = "",
        @SerialName("category_name") val categoryName: String = ""
    )

    @Serializable
    data class LiveStream(
        @SerialName("num") val num: JsonElement? = null,
        @SerialName("name") val name: String = "",
        @SerialName("stream_id") val streamId: JsonElement? = null,
        @SerialName("id") val id: JsonElement? = null,
        @SerialName("stream_icon") val streamIcon: String? = null,
        @SerialName("category_id") val categoryId: JsonElement? = null,
        @SerialName("category_ids") val categoryIds: JsonElement? = null,
        @SerialName("epg_channel_id") val epgChannelId: JsonElement? = null,
        @SerialName("tvg_id") val tvgId: JsonElement? = null,
        @SerialName("direct_source") val directSource: String? = null
    )

    @Serializable
    data class VodStream(
        @SerialName("num") val num: Int = 0,
        @SerialName("name") val name: String = "",
        @SerialName("stream_id") val streamId: Int = 0,
        @SerialName("stream_icon") val streamIcon: String? = null,
        @SerialName("category_id") val categoryId: String? = null,
        @SerialName("container_extension") val containerExtension: String? = null,
        @SerialName("cover") val cover: String? = null,
        @SerialName("cover_big") val coverBig: String? = null,
        @SerialName("added") val added: JsonElement? = null,
        @SerialName("last_modified") val lastModified: JsonElement? = null,
        @SerialName("rating") val rating: JsonElement? = null,
        @SerialName("rating_5based") val rating5Based: JsonElement? = null,
        @SerialName("country") val country: String? = null,
        @SerialName("year") val year: JsonElement? = null,
        @SerialName("plot") val plot: String? = null,
        @SerialName("genre") val genre: String? = null
    )

    @Serializable
    data class SeriesStream(
        @SerialName("num") val num: Int = 0,
        @SerialName("name") val name: String = "",
        @SerialName("series_id") val seriesId: Int = 0,
        @SerialName("cover") val cover: String? = null,
        @SerialName("plot") val plot: String? = null,
        @SerialName("category_id") val categoryId: String? = null,
        @SerialName("backdrop_path") val backdropPath: JsonElement? = null,
        @SerialName("stream_icon") val streamIcon: String? = null,
        @SerialName("added") val added: JsonElement? = null,
        @SerialName("last_modified") val lastModified: JsonElement? = null,
        @SerialName("rating") val rating: JsonElement? = null,
        @SerialName("rating_5based") val rating5Based: JsonElement? = null,
        @SerialName("country") val country: String? = null,
        @SerialName("year") val year: JsonElement? = null,
        @SerialName("genre") val genre: String? = null
    )

    @Serializable
    data class EpisodeDto(
        @SerialName("id") val id: String = "",
        @SerialName("episode_num") val episodeNum: Int = 0,
        @SerialName("title") val title: String = "",
        @SerialName("container_extension") val containerExtension: String? = null,
        @SerialName("season") val season: Int = 0,
        @SerialName("direct_source") val directSource: String? = null,
        @SerialName("info") val info: EpisodeInfoDto? = null
    )

    @Serializable
    data class EpisodeInfoDto(
        @SerialName("movie_image") val movieImage: String? = null,
        @SerialName("plot") val plot: String? = null
    )

    fun loadCatalog(baseUrl: String, username: String, password: String): Catalog {
        val host = normalizeBase(baseUrl)
        val probe = getRaw(host, username, password, null)
        if (probe.contains("\"auth\":0") || probe.contains("\"auth\": 0")) {
            error("Xtream login rejected (auth=0). Check URL/user/pass.")
        }
        providerZone = parseServerInfoZone(probe)

        val liveCatsRaw = getList<XtreamCategory>(host, username, password, "get_live_categories")
        val liveStreams = getList<LiveStream>(host, username, password, "get_live_streams")
        val liveCategories = liveCatsRaw.map {
            Category(
                id = "live-${it.categoryId}",
                name = it.categoryName.ifBlank { "Live" },
                kind = ContentKind.LIVE
            )
        }
        val liveItems = LiveChannelMapping.sortLiveChannels(
            LiveChannelMapping.dedupeLiveChannels(
                liveStreams.mapNotNull { s ->
                    liveStreamToMediaItem(s, host, username, password, liveCategories)
                }
            )
        )
        if (liveItems.isEmpty()) {
            error("No live channels returned. Check credentials or server.")
        }

        val warnings = mutableListOf<String>()
        var vodCategories: List<Category> = emptyList()
        var vodItems: List<MediaItem> = emptyList()
        try {
            val vodCatsRaw = getList<XtreamCategory>(host, username, password, "get_vod_categories")
            val vodStreams = getList<VodStream>(host, username, password, "get_vod_streams")
            vodCategories = vodCatsRaw.map {
                Category(
                    id = "vod-${it.categoryId}",
                    name = it.categoryName.ifBlank { "Movies" },
                    kind = ContentKind.VOD
                )
            }
            vodItems = vodStreams.mapNotNull { s ->
                val sid = s.streamId
                if (sid <= 0) return@mapNotNull null
                val ext = s.containerExtension?.trim()?.removePrefix(".")?.takeIf { it.isNotBlank() } ?: "mp4"
                val poster = firstNonBlank(s.coverBig, s.cover, s.streamIcon)
                MediaItem(
                    id = "vod-$sid",
                    name = s.name.ifBlank { "Title $sid" },
                    streamUrl = "$host/movie/$username/$password/$sid.$ext",
                    categoryId = s.categoryId?.let { "vod-$it" },
                    kind = ContentKind.VOD,
                    logoUrl = s.streamIcon?.takeIf { it.isNotBlank() },
                    posterUrl = poster,
                    groupTitle = vodCategories.find { it.id == "vod-${s.categoryId}" }?.name,
                    xtreamStreamId = sid,
                    playable = true,
                    addedEpoch = parseAddedEpoch(s.added, s.lastModified),
                    rating = stringFromJson(s.rating),
                    rating5Based = doubleFromJson(s.rating5Based),
                    country = s.country?.takeIf { it.isNotBlank() },
                    year = yearFromJson(s.year),
                    plot = s.plot?.takeIf { it.isNotBlank() },
                    genre = s.genre?.takeIf { it.isNotBlank() }
                )
            }
            if (vodItems.isEmpty()) warnings += "VOD list empty. Live TV still available."
        } catch (t: Throwable) {
            warnings += "VOD load failed (${t.message}). Live TV still available."
        }

        var seriesCategories: List<Category> = emptyList()
        var seriesItems: List<MediaItem> = emptyList()
        try {
            val seriesCatsRaw = getList<XtreamCategory>(host, username, password, "get_series_categories")
            val seriesList = getList<SeriesStream>(host, username, password, "get_series")
            seriesCategories = seriesCatsRaw.map {
                Category(
                    id = "series-${it.categoryId}",
                    name = it.categoryName.ifBlank { "Series" },
                    kind = ContentKind.SERIES
                )
            }
            seriesItems = seriesList.mapNotNull { s ->
                val sid = s.seriesId
                if (sid <= 0) return@mapNotNull null
                val poster = firstNonBlank(s.cover, s.streamIcon)
                val backdrop = firstBackdrop(s.backdropPath)
                MediaItem(
                    id = "series-$sid",
                    name = s.name.ifBlank { "Series $sid" },
                    streamUrl = "",
                    categoryId = s.categoryId?.let { "series-$it" },
                    kind = ContentKind.SERIES,
                    logoUrl = s.streamIcon?.takeIf { it.isNotBlank() },
                    posterUrl = poster,
                    backdropUrl = backdrop,
                    groupTitle = seriesCategories.find { it.id == "series-${s.categoryId}" }?.name,
                    xtreamStreamId = sid,
                    playable = false,
                    addedEpoch = parseAddedEpoch(s.added, s.lastModified),
                    rating = stringFromJson(s.rating),
                    rating5Based = doubleFromJson(s.rating5Based),
                    country = s.country?.takeIf { it.isNotBlank() },
                    year = yearFromJson(s.year),
                    plot = s.plot?.takeIf { it.isNotBlank() },
                    genre = s.genre?.takeIf { it.isNotBlank() }
                )
            }
            if (seriesItems.isEmpty()) warnings += "Series list empty."
        } catch (t: Throwable) {
            warnings += "Series load failed (${t.message}). Live/Movies still available."
        }

        return Catalog(
            liveCategories = liveCategories,
            vodCategories = vodCategories,
            seriesCategories = seriesCategories,
            liveItems = liveItems,
            vodItems = vodItems,
            seriesItems = seriesItems,
            warnings = warnings
        )
    }

    /**
     * Map one Xtream live stream to a catalog row.
     * Playback URL always uses `stream_id` (never `num` / list index / epg_channel_id).
     */
    internal fun liveStreamToMediaItem(
        s: LiveStream,
        host: String,
        username: String,
        password: String,
        liveCategories: List<Category>
    ): MediaItem? {
        val ids = LiveChannelMapping.parseXtreamLiveIds(
            streamIdEl = s.streamId,
            numEl = s.num,
            epgChannelIdEl = s.epgChannelId,
            categoryIdEl = s.categoryId,
            categoryIdsEl = s.categoryIds,
            fallbackIdEl = s.id,
            tvgIdEl = s.tvgId
        )
        val sid = ids.streamId
        if (sid <= 0) return null
        val catalogCatIds = LiveChannelMapping.catalogCategoryIds(ids.rawCategoryIds)
        val direct = s.directSource?.trim()?.takeIf { it.isNotBlank() }
        return MediaItem(
            id = "live-$sid",
            name = s.name.ifBlank { "Channel $sid" },
            streamUrl = direct ?: "$host/live/$username/$password/$sid.m3u8",
            categoryId = catalogCatIds.firstOrNull(),
            kind = ContentKind.LIVE,
            logoUrl = s.streamIcon?.takeIf { it.isNotBlank() },
            groupTitle = catalogCatIds.firstNotNullOfOrNull { cid ->
                liveCategories.find { it.id == cid }?.name
            },
            xtreamStreamId = sid,
            playable = true,
            channelNum = ids.channelNum,
            epgChannelId = ids.epgChannelId,
            categoryIds = catalogCatIds
        )
    }

    fun loadSeriesDetail(
        baseUrl: String,
        username: String,
        password: String,
        seriesId: Int,
        seriesName: String = "",
        fallbackPoster: String? = null,
        fallbackBackdrop: String? = null
    ): SeriesDetail {
        val host = normalizeBase(baseUrl)
        val body = getRaw(
            host, username, password, "get_series_info",
            extra = mapOf("series_id" to seriesId.toString())
        )
        if (body.isBlank()) error("Empty series info for $seriesId")
        val root = json.parseToJsonElement(body).jsonObject
        val info = root["info"]?.jsonObject
        val name = info?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: seriesName.ifBlank { "Series $seriesId" }
        val plot = info?.get("plot")?.jsonPrimitive?.contentOrNull
        val poster = firstNonBlank(
            info?.get("cover")?.jsonPrimitive?.contentOrNull,
            info?.get("movie_image")?.jsonPrimitive?.contentOrNull,
            fallbackPoster
        )
        val backdrop = firstBackdrop(info?.get("backdrop_path")) ?: fallbackBackdrop

        val episodesEl = root["episodes"]
        val episodes = mutableListOf<SeriesEpisode>()
        when (episodesEl) {
            is JsonObject -> {
                for ((seasonKey, seasonVal) in episodesEl) {
                    val seasonNum = seasonKey.toIntOrNull() ?: 0
                    val arr = seasonVal as? JsonArray ?: continue
                    for (epEl in arr) {
                        val ep = runCatching { json.decodeFromJsonElement<EpisodeDto>(epEl) }.getOrNull() ?: continue
                        val eid = ep.id.trim()
                        if (eid.isEmpty()) continue
                        val ext = ep.containerExtension?.trim()?.removePrefix(".")?.takeIf { it.isNotBlank() } ?: "mp4"
                        val season = if (ep.season > 0) ep.season else seasonNum
                        val direct = ep.directSource?.trim()?.takeIf { it.isNotBlank() }
                        episodes += SeriesEpisode(
                            id = eid,
                            title = ep.title.ifBlank { "Episode ${ep.episodeNum}" },
                            season = season,
                            episodeNum = ep.episodeNum,
                            streamUrl = direct ?: "$host/series/$username/$password/$eid.$ext",
                            containerExtension = ext,
                            posterUrl = ep.info?.movieImage?.takeIf { it.isNotBlank() } ?: poster
                        )
                    }
                }
            }
            is JsonArray -> {
                for (epEl in episodesEl) {
                    val ep = runCatching { json.decodeFromJsonElement<EpisodeDto>(epEl) }.getOrNull() ?: continue
                    val eid = ep.id.trim()
                    if (eid.isEmpty()) continue
                    val ext = ep.containerExtension?.trim()?.removePrefix(".")?.takeIf { it.isNotBlank() } ?: "mp4"
                    val direct = ep.directSource?.trim()?.takeIf { it.isNotBlank() }
                    episodes += SeriesEpisode(
                        id = eid,
                        title = ep.title.ifBlank { "Episode ${ep.episodeNum}" },
                        season = ep.season,
                        episodeNum = ep.episodeNum,
                        streamUrl = direct ?: "$host/series/$username/$password/$eid.$ext",
                        containerExtension = ext,
                        posterUrl = ep.info?.movieImage?.takeIf { it.isNotBlank() } ?: poster
                    )
                }
            }
            else -> Unit
        }
        episodes.sortWith(compareBy({ it.season }, { it.episodeNum }))
        val cast = pickCast(info)
        val rating = stringFromJson(info?.get("rating"))
            ?: info?.get("rating")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val year = yearFromJson(info?.get("year"))
            ?: yearFromJson(info?.get("releasedate") ?: info?.get("releaseDate"))
        val genre = info?.get("genre")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return SeriesDetail(
            seriesId = seriesId,
            name = name,
            plot = plot,
            posterUrl = poster,
            backdropUrl = backdrop,
            cast = cast,
            rating = rating,
            year = year,
            genre = genre,
            episodes = episodes
        )
    }

    /**
     * Fetch VOD movie detail via get_vod_info (plot + cast/actors) before play.
     * Parses info flexibly; empty cast becomes null (UI shows "Cast unavailable").
     */
    fun loadVodDetail(
        baseUrl: String,
        username: String,
        password: String,
        streamId: Int,
        fallbackName: String = "",
        fallbackPoster: String? = null,
        fallbackBackdrop: String? = null,
        fallbackStreamUrl: String = "",
        fallbackPlot: String? = null,
        fallbackRating: String? = null,
        fallbackYear: Int? = null,
        fallbackGenre: String? = null,
        catalogId: String = "",
        categoryId: String? = null
    ): VodDetail {
        val host = normalizeBase(baseUrl)
        val body = getRaw(
            host, username, password, "get_vod_info",
            extra = mapOf("vod_id" to streamId.toString())
        )
        val info: JsonObject?
        val movieData: JsonObject?
        if (body.isBlank()) {
            info = null
            movieData = null
        } else {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            info = root?.get("info")?.asJsonObjectOrNull()
            movieData = root?.get("movie_data")?.asJsonObjectOrNull()
                ?: root?.get("data")?.asJsonObjectOrNull()
        }

        val name = firstNonBlank(
            info?.stringField("name"),
            movieData?.stringField("name"),
            fallbackName,
            "Title $streamId"
        )!!

        val plot = firstNonBlank(
            info?.stringField("plot"),
            info?.stringField("description"),
            info?.stringField("desc"),
            movieData?.stringField("plot"),
            fallbackPlot
        )

        val cast = pickCast(info) ?: pickCast(movieData)

        val rating = firstNonBlank(
            stringFromJson(info?.get("rating")),
            info?.stringField("rating"),
            movieData?.stringField("rating"),
            fallbackRating
        )

        val year = yearFromJson(info?.get("year"))
            ?: yearFromJson(info?.get("releasedate") ?: info?.get("releaseDate") ?: info?.get("release_date"))
            ?: yearFromJson(movieData?.get("year"))
            ?: fallbackYear

        val genre = firstNonBlank(
            info?.stringField("genre"),
            movieData?.stringField("genre"),
            fallbackGenre
        )

        val poster = firstNonBlank(
            info?.stringField("movie_image"),
            info?.stringField("cover_big"),
            info?.stringField("cover"),
            info?.stringField("stream_icon"),
            movieData?.stringField("stream_icon"),
            fallbackPoster
        )

        val backdrop = firstBackdrop(info?.get("backdrop_path"))
            ?: firstNonBlank(info?.stringField("backdrop"), fallbackBackdrop)

        val ext = firstNonBlank(
            movieData?.stringField("container_extension"),
            info?.stringField("container_extension")
        )?.trim()?.removePrefix(".")?.takeIf { it.isNotBlank() } ?: "mp4"

        val direct = firstNonBlank(
            movieData?.stringField("direct_source"),
            info?.stringField("direct_source")
        )
        val streamUrl = direct
            ?: fallbackStreamUrl.takeIf { it.isNotBlank() }
            ?: "$host/movie/$username/$password/$streamId.$ext"

        return VodDetail(
            streamId = streamId,
            name = name,
            plot = plot,
            cast = cast,
            rating = rating,
            year = year,
            genre = genre,
            posterUrl = poster,
            backdropUrl = backdrop,
            streamUrl = streamUrl,
            catalogId = catalogId.ifBlank { "vod-$streamId" },
            categoryId = categoryId
        )
    }

    /** Prefer cast / actors / starring fields; join arrays; blank → null. */
    private fun pickCast(info: JsonObject?): String? {
        if (info == null) return null
        val candidates = listOf("cast", "actors", "actor", "starring", "stars")
        for (key in candidates) {
            val el = info[key] ?: continue
            val text = when (el) {
                is JsonPrimitive -> el.contentOrNull?.trim()
                is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotBlank() } }
                    .joinToString(", ")
                    .takeIf { it.isNotBlank() }
                else -> null
            }
            if (!text.isNullOrBlank() && text != "null") return text
        }
        return null
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.stringField(key: String): String? {
        val el = this[key] ?: return null
        return when (el) {
            is JsonPrimitive -> el.contentOrNull?.trim()?.takeIf { it.isNotBlank() && it != "null" }
            else -> null
        }
    }


    fun loadShortEpg(
        baseUrl: String,
        username: String,
        password: String,
        streamId: Int,
        limit: Int = 12
    ): ChannelEpg {
        val host = normalizeBase(baseUrl)
        val body = getRaw(
            host, username, password, "get_short_epg",
            extra = mapOf(
                "stream_id" to streamId.toString(),
                "limit" to limit.toString()
            )
        )
        return ChannelEpg(streamId = streamId, programs = parseEpgListings(body, streamId))
    }

    fun loadSimpleEpgTable(
        baseUrl: String,
        username: String,
        password: String,
        streamId: Int
    ): ChannelEpg {
        val host = normalizeBase(baseUrl)
        val body = getRaw(
            host, username, password, "get_simple_data_table",
            extra = mapOf("stream_id" to streamId.toString())
        )
        val programs = parseEpgListings(body, streamId)
        return ChannelEpg(streamId = streamId, programs = programs)
    }

    internal fun parseEpgListings(
        body: String,
        streamId: Int,
        displayZone: ZoneId = GuideTime.zone(),
        nowMs: Long = GuideTime.nowMs()
    ): List<EpgProgram> {
        if (body.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val listings: JsonArray = when (root) {
            is JsonObject -> {
                val el = root["epg_listings"] ?: root["listings"] ?: return emptyList()
                el as? JsonArray ?: return emptyList()
            }
            is JsonArray -> root
            else -> return emptyList()
        }
        data class Raw(
            val id: String,
            val title: String,
            val description: String?,
            val primary: EpgTime.Instants,
            val localText: EpgTime.Instants
        )
        val textZone = providerZone ?: displayZone
        val rawRows = ArrayList<Raw>(listings.size)
        for (el in listings) {
            val obj = el as? JsonObject ?: continue
            val startTs = obj["start_timestamp"]?.jsonPrimitive?.contentOrNull
            val endTs = obj["stop_timestamp"]?.jsonPrimitive?.contentOrNull
            val startText = obj["start"]?.jsonPrimitive?.contentOrNull
            val endText = obj["end"]?.jsonPrimitive?.contentOrNull
            val startMs = EpgTime.fromFields(startTs, startText, displayZone, providerZone)
            val endMs = EpgTime.fromFields(endTs, endText, displayZone, providerZone)
            if (startMs <= 0L || endMs <= 0L || endMs <= startMs) continue
            val localStart = startText?.let { EpgTime.fromNaiveInZone(it, textZone) } ?: startMs
            val localEnd = endText?.let { EpgTime.fromNaiveInZone(it, textZone) } ?: endMs
            val title = decodeEpgText(
                obj["title"]?.jsonPrimitive?.contentOrNull
                    ?: obj["name"]?.jsonPrimitive?.contentOrNull
            ).ifBlank { "Program" }
            val desc = decodeEpgText(
                obj["description"]?.jsonPrimitive?.contentOrNull
                    ?: obj["desc"]?.jsonPrimitive?.contentOrNull
            ).takeIf { it.isNotBlank() }
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
                ?: obj["epg_id"]?.jsonPrimitive?.contentOrNull
                ?: "$streamId-$startMs"
            rawRows += Raw(
                id = id,
                title = title,
                description = desc,
                primary = EpgTime.Instants(startMs, endMs),
                localText = if (localStart > 0L && localEnd > localStart) {
                    EpgTime.Instants(localStart, localEnd)
                } else {
                    EpgTime.Instants(startMs, endMs)
                }
            )
        }
        val aligned = EpgTime.alignToNow(
            rawRows.map { it.primary },
            rawRows.map { it.localText },
            nowMs
        )
        return rawRows.zip(aligned) { row, times ->
            EpgProgram(
                id = row.id,
                title = row.title,
                description = row.description,
                startMs = times.startMs,
                endMs = times.endMs,
                channelStreamId = streamId
            )
        }.sortedBy { it.startMs }
    }

    internal fun epgTimeMs(obj: JsonObject, tsKey: String, textKey: String): Long {
        val ts = obj[tsKey]?.jsonPrimitive?.contentOrNull
        val text = obj[textKey]?.jsonPrimitive?.contentOrNull
        return EpgTime.fromFields(ts, text, GuideTime.zone(), providerZone)
    }

    internal fun parseServerInfoZone(body: String): ZoneId? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return null
        val info = (root["server_info"] as? JsonObject) ?: root
        val named = info["timezone"]?.jsonPrimitive?.contentOrNull
            ?.let { OsTimeZone.zoneOrNull(it) }
        if (named != null) return named
        val timeNow = info["time_now"]?.jsonPrimitive?.contentOrNull
        val tsNow = info["timestamp_now"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        return EpgTime.inferProviderZone(timeNow, tsNow)
    }

    private fun decodeEpgText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        // Many Xtream panels base64-encode title/description
        return try {
            val decoded = String(Base64.getDecoder().decode(trimmed), Charsets.UTF_8)
            if (decoded.any { it.code < 9 && it != '\n' && it != '\r' && it != '\t' }) trimmed
            else decoded.trim().ifBlank { trimmed }
        } catch (_: Exception) {
            trimmed
        }
    }


    private fun stringFromJson(el: JsonElement?): String? {
        if (el == null || el is kotlinx.serialization.json.JsonNull) return null
        val prim = el as? JsonPrimitive ?: return null
        return prim.contentOrNull?.trim()?.takeIf { it.isNotBlank() && it != "null" && it != "0" && it != "0.0" }
            ?: prim.longOrNull?.takeIf { it > 0 }?.toString()
    }

    private fun doubleFromJson(el: JsonElement?): Double? {
        if (el == null || el is kotlinx.serialization.json.JsonNull) return null
        val prim = el as? JsonPrimitive ?: return null
        prim.contentOrNull?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { return it }
        return prim.longOrNull?.toDouble()?.takeIf { it > 0.0 }
    }

    private fun yearFromJson(el: JsonElement?): Int? {
        val raw = stringFromJson(el)?.trim()?.takeIf { it.isNotBlank() }
            ?: doubleFromJson(el)?.toInt()?.toString()
            ?: return null
        val digits = Regex("""(19|20)\d{2}""").find(raw)?.value ?: raw
        return digits.toIntOrNull()?.takeIf { it in 1888..2100 }
    }

    private fun parseAddedEpoch(vararg candidates: JsonElement?): Long {
        for (el in candidates) {
            val v = epochFromJson(el)
            if (v > 0L) return v
        }
        return 0L
    }

    private fun epochFromJson(el: JsonElement?): Long {
        if (el == null || el is kotlinx.serialization.json.JsonNull) return 0L
        val prim = el as? JsonPrimitive ?: return 0L
        prim.longOrNull?.takeIf { it > 0L }?.let { return it }
        return prim.contentOrNull?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun firstBackdrop(el: JsonElement?): String? {
        if (el == null || el is kotlinx.serialization.json.JsonNull) return null
        return when (el) {
            is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { u -> u.isNotBlank() } }.firstOrNull()
            is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private inline fun <reified T> getList(
        host: String,
        username: String,
        password: String,
        action: String
    ): List<T> {
        val body = getRaw(host, username, password, action)
        if (body.isBlank() || body == "[]") return emptyList()
        val trimmed = body.trimStart()
        if (trimmed.startsWith("{")) {
            if (trimmed.contains("\"auth\":0") || trimmed.contains("\"auth\": 0")) {
                error("Xtream login rejected (auth=0). Check URL/user/pass.")
            }
            return emptyList()
        }
        return try {
            json.decodeFromString(body)
        } catch (_: Exception) {
            recoverJsonObjectArray(trimmed)
        }
    }

    private inline fun <reified T> recoverJsonObjectArray(raw: String): List<T> {
        val out = ArrayList<T>()
        val startBracket = raw.indexOf('[')
        if (startBracket < 0) return emptyList()
        var i = startBracket + 1
        val n = raw.length
        while (i < n) {
            while (i < n && (raw[i].isWhitespace() || raw[i] == ',')) i++
            if (i >= n || raw[i] == ']') break
            if (raw[i] != '{') {
                while (i < n && raw[i] != '{' && raw[i] != ']') i++
                continue
            }
            val objStart = i
            var depth = 0
            var inString = false
            var escape = false
            var closed = false
            while (i < n) {
                val c = raw[i]
                if (inString) {
                    when {
                        escape -> escape = false
                        c == '\\' -> escape = true
                        c == '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                val slice = raw.substring(objStart, i + 1)
                                runCatching { json.decodeFromString<T>(slice) }.onSuccess { out.add(it) }
                                i++
                                closed = true
                                break
                            }
                        }
                    }
                }
                i++
            }
            if (!closed) break
        }
        return out
    }

    private fun getRaw(
        host: String,
        username: String,
        password: String,
        action: String?,
        extra: Map<String, String> = emptyMap()
    ): String {
        val builder = host.toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", username)
            .addQueryParameter("password", password)
        if (action != null) builder.addQueryParameter("action", action)
        extra.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        val request = Request.Builder()
            .url(builder.build())
            .get()
            .header("User-Agent", "TotalIPTVPro-Desktop/1.0")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Xtream ${action ?: "auth"} failed: HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun normalizeBase(raw: String): String {
        var u = raw.trim().removeSuffix("/")
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            u = "http://$u"
        }
        return u
    }
}
