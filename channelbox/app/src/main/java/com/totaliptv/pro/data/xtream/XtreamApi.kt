package com.totaliptv.pro.data.xtream

import android.util.Base64
import android.util.Log
import com.totaliptv.pro.data.EpgTime
import com.totaliptv.pro.data.LiveChannelMapping
import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.EpgNowNext
import com.totaliptv.pro.data.model.EpgProgram
import com.totaliptv.pro.data.model.MediaItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.ZoneId
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
    internal var providerZone: ZoneId? = null

    companion object {
        private const val TAG = "TotalIPTV.Xtream"

        /** Coerce Xtream numeric ids that arrive as int, long, double, or numeric string. */
        fun flexibleInt(raw: JsonElement?): Int {
            if (raw == null) return 0
            return when (raw) {
                is JsonPrimitive -> {
                    raw.intOrNull
                        ?: raw.longOrNull?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else 0 }
                        ?: raw.doubleOrNull?.toInt()
                        ?: raw.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
                        ?: 0
                }
                else -> 0
            }
        }
    }

    @Serializable
    data class XtreamCategory(
        @SerialName("category_id") val categoryId: String = "",
        @SerialName("category_name") val categoryName: String = ""
    )

    @Serializable
    data class LiveStream(
        // Providers sometimes send num/stream_id as string or float — never rely on bare Int.
        @SerialName("num") val numRaw: JsonElement? = null,
        @SerialName("name") val name: String = "",
        @SerialName("stream_id") val streamIdRaw: JsonElement? = null,
        @SerialName("id") val idRaw: JsonElement? = null,
        @SerialName("stream_icon") val streamIcon: String? = null,
        @SerialName("category_id") val categoryId: JsonElement? = null,
        @SerialName("category_ids") val categoryIds: JsonElement? = null,
        @SerialName("epg_channel_id") val epgChannelId: JsonElement? = null,
        @SerialName("tvg_id") val tvgId: JsonElement? = null,
        @SerialName("direct_source") val directSource: String? = null,
        @SerialName("added") val added: JsonElement? = null
    ) {
        val num: Int get() = LiveChannelMapping.parsePositiveInt(numRaw)
        val streamId: Int get() = LiveChannelMapping.parsePositiveInt(streamIdRaw)
    }

    @Serializable
    data class VodStream(
        @SerialName("num") val numRaw: JsonElement? = null,
        @SerialName("name") val name: String = "",
        @SerialName("stream_id") val streamIdRaw: JsonElement? = null,
        @SerialName("stream_icon") val streamIcon: String? = null,
        @SerialName("category_id") val categoryId: String? = null,
        @SerialName("container_extension") val containerExtension: String? = null,
        @SerialName("cover") val cover: String? = null,
        @SerialName("cover_big") val coverBig: String? = null,
        /** Unix epoch seconds (string or number) when the title was added. */
        @SerialName("added") val added: JsonElement? = null,
        @SerialName("rating") val rating: String? = null,
        @SerialName("rating_5based") val rating5based: String? = null,
        @SerialName("youtube_trailer") val youtubeTrailer: String? = null
    ) {
        val num: Int get() = XtreamApi.flexibleInt(numRaw)
        val streamId: Int get() = XtreamApi.flexibleInt(streamIdRaw)
    }

    @Serializable
    data class SeriesStream(
        @SerialName("num") val numRaw: JsonElement? = null,
        @SerialName("name") val name: String = "",
        @SerialName("series_id") val seriesIdRaw: JsonElement? = null,
        @SerialName("cover") val cover: String? = null,
        @SerialName("cover_big") val coverBig: String? = null,
        @SerialName("stream_icon") val streamIcon: String? = null,
        @SerialName("category_id") val categoryId: String? = null,
        @SerialName("plot") val plot: String? = null,
        @SerialName("rating") val rating: String? = null,
        @SerialName("rating_5based") val rating5based: String? = null,
        @SerialName("youtube_trailer") val youtubeTrailer: String? = null,
        @SerialName("releaseDate") val releaseDate: String? = null,
        @SerialName("genre") val genre: String? = null,
        @SerialName("last_modified") val lastModified: JsonElement? = null,
        @SerialName("added") val added: JsonElement? = null
    ) {
        val num: Int get() = XtreamApi.flexibleInt(numRaw)
        val seriesId: Int get() = XtreamApi.flexibleInt(seriesIdRaw)
    }

    @Serializable
    data class ShortEpgListing(
        @SerialName("id") val id: String? = null,
        @SerialName("title") val title: String = "",
        @SerialName("description") val description: String? = null,
        @SerialName("start") val start: String? = null,
        @SerialName("end") val end: String? = null,
        @SerialName("start_timestamp") val startTimestamp: Long? = null,
        @SerialName("stop_timestamp") val stopTimestamp: Long? = null,
        @SerialName("end_timestamp") val endTimestamp: Long? = null
    )

    data class Catalog(
        val liveCategories: List<Category>,
        val vodCategories: List<Category>,
        val seriesCategories: List<Category> = emptyList(),
        val liveItems: List<MediaItem>,
        val vodItems: List<MediaItem>,
        val seriesItems: List<MediaItem> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    data class LiveCatalog(
        val categories: List<Category>,
        val items: List<MediaItem>
    )

    data class VodCatalog(
        val categories: List<Category>,
        val items: List<MediaItem>,
        val warnings: List<String> = emptyList()
    )

    data class SeriesCatalog(
        val categories: List<Category>,
        val items: List<MediaItem>,
        val warnings: List<String> = emptyList()
    )

    data class Credentials(
        val baseUrl: String,
        val username: String,
        val password: String
    )

    /** Auth probe + live categories/streams only — used to paint home ASAP. */
    fun loadLiveCatalog(baseUrl: String, username: String, password: String): LiveCatalog {
        val host = normalizeBase(baseUrl)
        runCatching {
            val probe = getRaw(host, username, password, null)
            if (probe.contains("\"auth\":0") || probe.contains("\"auth\": 0")) {
                error("Xtream login rejected (auth=0). Check URL/user/pass.")
            }
            providerZone = parseServerInfoZone(probe)
        }

        val liveCatsRaw = getListFlexible<XtreamCategory>(host, username, password, "get_live_categories")
        val liveStreams = getListFlexible<LiveStream>(host, username, password, "get_live_streams")

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
        // Spot-check name/stream_id pairing (num is display order only — never playback id).
        liveItems.take(5).forEach { m ->
            Log.i(
                TAG,
                "liveMap name=${m.name} id=${m.id} sid=${m.xtreamStreamId} num=${m.channelNum} url=${m.streamUrl}"
            )
        }
        return LiveCatalog(liveCategories, liveItems)
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
            streamIdEl = s.streamIdRaw,
            numEl = s.numRaw,
            epgChannelIdEl = s.epgChannelId,
            categoryIdEl = s.categoryId,
            categoryIdsEl = s.categoryIds,
            fallbackIdEl = s.idRaw,
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
            posterUrl = null,
            groupTitle = catalogCatIds.firstNotNullOfOrNull { cid ->
                liveCategories.find { it.id == cid }?.name
            },
            epgChannelId = ids.epgChannelId,
            xtreamStreamId = sid,
            addedMs = parseXtreamAdded(s.added),
            channelNum = ids.channelNum.takeIf { it > 0 },
            categoryIds = catalogCatIds
        )
    }

    /**
     * VOD categories/streams. Never throws for parse/network of VOD alone —
     * returns empty lists + warnings so live home stays usable.
     */
    fun loadVodCatalog(baseUrl: String, username: String, password: String): VodCatalog {
        val host = normalizeBase(baseUrl)
        val warnings = mutableListOf<String>()
        return try {
            val vodCatsRaw = getListFlexible<XtreamCategory>(host, username, password, "get_vod_categories")
            val vodStreams = getListFlexible<VodStream>(host, username, password, "get_vod_streams")
            val vodCategories = vodCatsRaw.map {
                Category(
                    id = "vod-${it.categoryId}",
                    name = it.categoryName.ifBlank { "VOD" },
                    kind = ContentKind.VOD
                )
            }
            val vodItems = vodStreams.map { s ->
                val ext = s.containerExtension?.trim()?.removePrefix(".")?.takeIf { it.isNotBlank() } ?: "mp4"
                val poster = listOf(s.coverBig, s.cover, s.streamIcon).firstOrNull { !it.isNullOrBlank() }
                MediaItem(
                    id = "vod-${s.streamId}",
                    name = s.name.ifBlank { "Title ${s.streamId}" },
                    streamUrl = "$host/movie/$username/$password/${s.streamId}.$ext",
                    categoryId = s.categoryId?.let { "vod-$it" },
                    kind = ContentKind.VOD,
                    logoUrl = s.streamIcon?.takeIf { it.isNotBlank() },
                    posterUrl = poster,
                    groupTitle = vodCategories.find { it.id == "vod-${s.categoryId}" }?.name,
                    xtreamStreamId = s.streamId,
                    addedMs = parseXtreamAdded(s.added),
                    rating = normalizeRating(s.rating, s.rating5based),
                    youtubeTrailer = s.youtubeTrailer?.trim()?.takeIf { it.isNotBlank() }
                )
            }
            if (vodItems.isEmpty() && vodCategories.isEmpty()) {
                warnings += "VOD list empty or unreadable. Live TV still available."
            }
            VodCatalog(vodCategories, vodItems, warnings)
        } catch (t: Throwable) {
            Log.w(TAG, "VOD load failed; keeping live catalog", t)
            VodCatalog(
                emptyList(),
                emptyList(),
                listOf("VOD list failed (${t.message ?: t.javaClass.simpleName}). Live TV still available.")
            )
        }
    }


    /**
     * Series categories + series list via get_series_categories / get_series.
     * Items are covers only; episode URL resolved later via get_series_info.
     * Never throws — empty + warnings on failure so Movies/Live stay usable.
     */
    fun loadSeriesCatalog(baseUrl: String, username: String, password: String): SeriesCatalog {
        val host = normalizeBase(baseUrl)
        val warnings = mutableListOf<String>()
        return try {
            val catsRaw = getListFlexible<XtreamCategory>(host, username, password, "get_series_categories")
            val seriesRaw = getListFlexible<SeriesStream>(host, username, password, "get_series")
            val categories = catsRaw.map {
                Category(
                    id = "series-${it.categoryId}",
                    name = it.categoryName.ifBlank { "Series" },
                    kind = ContentKind.SERIES
                )
            }
            val items = seriesRaw.map { s ->
                val sid = s.seriesId
                val poster = listOf(s.coverBig, s.cover, s.streamIcon).firstOrNull { !it.isNullOrBlank() }
                MediaItem(
                    id = "series-$sid",
                    name = s.name.ifBlank { "Series $sid" },
                    // Placeholder — resolve first episode on play via get_series_info
                    streamUrl = "$host/series/$username/$password/$sid",
                    categoryId = s.categoryId?.let { "series-$it" },
                    kind = ContentKind.SERIES,
                    logoUrl = s.streamIcon?.takeIf { it.isNotBlank() },
                    posterUrl = poster,
                    groupTitle = categories.find { it.id == "series-${s.categoryId}" }?.name,
                    xtreamStreamId = sid,
                    addedMs = parseXtreamAdded(s.added) ?: parseXtreamAdded(s.lastModified),
                    rating = normalizeRating(s.rating, s.rating5based),
                    youtubeTrailer = s.youtubeTrailer?.trim()?.takeIf { it.isNotBlank() }
                )
            }
            if (items.isEmpty() && categories.isEmpty()) {
                warnings += "Series list empty or unreadable."
            }
            SeriesCatalog(categories, items, warnings)
        } catch (t: Throwable) {
            Log.w(TAG, "Series load failed", t)
            SeriesCatalog(
                emptyList(),
                emptyList(),
                listOf("Series list failed (${t.message ?: t.javaClass.simpleName}).")
            )
        }
    }

    fun loadCatalog(baseUrl: String, username: String, password: String): Catalog {
        val live = loadLiveCatalog(baseUrl, username, password)
        val vod = loadVodCatalog(baseUrl, username, password)
        val series = loadSeriesCatalog(baseUrl, username, password)
        return Catalog(
            liveCategories = live.categories,
            vodCategories = vod.categories,
            seriesCategories = series.categories,
            liveItems = live.items,
            vodItems = vod.items,
            seriesItems = series.items,
            warnings = vod.warnings + series.warnings
        )
    }

    data class VodDetails(
        val posterUrl: String? = null,
        val youtubeTrailer: String? = null,
        val plot: String? = null,
        val rating: String? = null
    )

    fun fetchVodPoster(creds: Credentials, streamId: Int): String? =
        fetchVodDetails(creds, streamId).posterUrl

    /** Poster + youtube_trailer — uses get_vod_info or get_series_info. */
    fun fetchMediaDetails(creds: Credentials, streamId: Int, isSeries: Boolean): VodDetails =
        if (isSeries) fetchSeriesDetails(creds, streamId) else fetchVodDetails(creds, streamId)

    /** Poster + youtube_trailer from get_vod_info (list endpoint often omits trailer). */
    fun fetchVodDetails(creds: Credentials, streamId: Int): VodDetails {
        return runCatching {
            val host = normalizeBase(creds.baseUrl)
            val body = getRaw(
                host, creds.username, creds.password, "get_vod_info",
                extra = mapOf("vod_id" to streamId.toString())
            )
            if (body.isBlank()) return VodDetails()
            parseInfoTrailer(body, streamId, "get_vod_info")
        }.onFailure { Log.w(TAG, "get_vod_info failed for $streamId", it) }.getOrDefault(VodDetails())
    }

    /** Poster + youtube_trailer from get_series_info. */
    fun fetchSeriesDetails(creds: Credentials, streamId: Int): VodDetails {
        return runCatching {
            val host = normalizeBase(creds.baseUrl)
            val body = getRaw(
                host, creds.username, creds.password, "get_series_info",
                extra = mapOf("series_id" to streamId.toString())
            )
            if (body.isBlank()) return VodDetails()
            parseInfoTrailer(body, streamId, "get_series_info")
        }.onFailure { Log.w(TAG, "get_series_info details failed for $streamId", it) }.getOrDefault(VodDetails())
    }

    private fun parseInfoTrailer(body: String, streamId: Int, action: String): VodDetails {
        val root = json.parseToJsonElement(body).jsonObject
        val info = root["info"]?.jsonObject
        val movieData = root["movie_data"]?.jsonObject
        val poster = listOf(
            info?.get("cover_big")?.asString(),
            info?.get("movie_image")?.asString(),
            info?.get("cover")?.asString(),
            movieData?.get("stream_icon")?.asString()
        ).firstOrNull { !it.isNullOrBlank() }
        val trailer = listOf(
            info?.get("youtube_trailer")?.asString(),
            movieData?.get("youtube_trailer")?.asString(),
            root["youtube_trailer"]?.asString()
        ).mapNotNull { it?.trim()?.takeIf { s -> s.isNotBlank() } }.firstOrNull()
        val plot = listOf(
            info?.get("plot")?.asString(),
            info?.get("description")?.asString(),
            movieData?.get("plot")?.asString()
        ).mapNotNull { it?.trim()?.takeIf { s -> s.isNotBlank() } }.firstOrNull()
        val rating = normalizeRating(
            info?.get("rating")?.asString() ?: movieData?.get("rating")?.asString(),
            info?.get("rating_5based")?.asString() ?: movieData?.get("rating_5based")?.asString()
        )
        Log.i(TAG, "$action id=$streamId trailer=${trailer ?: "(none)"} poster=${!poster.isNullOrBlank()} plot=${!plot.isNullOrBlank()}")
        return VodDetails(posterUrl = poster, youtubeTrailer = trailer, plot = plot, rating = rating)
    }


    data class SeriesEpisode(
        val url: String,
        val title: String,
        val episodeId: Int,
        val season: Int = 1,
        val episodeNum: Int = 1
    )

    data class SeriesSeasonInfo(
        val season: Int,
        val episodes: List<SeriesEpisode>
    )

    data class SeriesInfo(
        val seasons: List<SeriesSeasonInfo>
    ) {
        fun firstEpisode(): SeriesEpisode? =
            seasons.asSequence().flatMap { it.episodes.asSequence() }.firstOrNull()

        fun findEpisode(episodeId: Int): SeriesEpisode? =
            seasons.asSequence().flatMap { it.episodes.asSequence() }
                .firstOrNull { it.episodeId == episodeId }

        /** Next episode in season order after [episodeId], or null if last. */
        fun nextAfter(episodeId: Int): SeriesEpisode? {
            val flat = seasons.flatMap { it.episodes }
            val idx = flat.indexOfFirst { it.episodeId == episodeId }
            return if (idx >= 0 && idx + 1 < flat.size) flat[idx + 1] else null
        }
    }

    /** Full season/episode tree from get_series_info. */
    fun fetchSeriesInfo(creds: Credentials, seriesId: Int): SeriesInfo? {
        return runCatching {
            val host = normalizeBase(creds.baseUrl)
            val body = getRaw(
                host, creds.username, creds.password, "get_series_info",
                extra = mapOf("series_id" to seriesId.toString())
            )
            if (body.isBlank()) return null
            val root = json.parseToJsonElement(body).jsonObject
            val episodesEl = root["episodes"] ?: return null
            data class Cand(val season: Int, val epNum: Int, val id: Int, val ext: String, val title: String)
            val candidates = mutableListOf<Cand>()
            fun ingest(season: Int, arr: JsonArray) {
                arr.forEachIndexed { idx, el ->
                    val obj = runCatching { el.jsonObject }.getOrNull() ?: return@forEachIndexed
                    val eid = obj["id"]?.asString()?.toIntOrNull()
                        ?: obj["episode_id"]?.asString()?.toIntOrNull()
                        ?: return@forEachIndexed
                    val ext = obj["container_extension"]?.asString()?.trim()?.removePrefix(".")
                        ?.ifBlank { null } ?: "mp4"
                    val title = obj["title"]?.asString()?.takeIf { it.isNotBlank() }
                        ?: "S${season}E${idx + 1}"
                    val epNum = obj["episode_num"]?.asString()?.toIntOrNull() ?: (idx + 1)
                    candidates += Cand(season, epNum, eid, ext, title)
                }
            }
            when (episodesEl) {
                is JsonArray -> ingest(1, episodesEl)
                else -> {
                    for ((seasonKey, arrEl) in episodesEl.jsonObject) {
                        val seasonNum = seasonKey.toIntOrNull() ?: continue
                        val arr = arrEl as? JsonArray ?: continue
                        ingest(seasonNum, arr)
                    }
                }
            }
            if (candidates.isEmpty()) return null
            val bySeason = candidates.groupBy { it.season }.toSortedMap()
            SeriesInfo(
                seasons = bySeason.map { (season, eps) ->
                    SeriesSeasonInfo(
                        season = season,
                        episodes = eps.sortedBy { it.epNum }.map { c ->
                            SeriesEpisode(
                                url = "$host/series/${creds.username}/${creds.password}/${c.id}.${c.ext}",
                                title = c.title,
                                episodeId = c.id,
                                season = c.season,
                                episodeNum = c.epNum
                            )
                        }
                    )
                }
            )
        }.onFailure { Log.w(TAG, "get_series_info failed for $seriesId", it) }.getOrNull()
    }

    /**
     * Resolve a playable episode URL for a series via get_series_info.
     * Picks season 1 episode 1 when available, else first episode found.
     * Episode id is used for resume keys (not the series id).
     */
    fun resolveSeriesEpisode(creds: Credentials, seriesId: Int): SeriesEpisode? =
        fetchSeriesInfo(creds, seriesId)?.firstEpisode()

    fun fetchShortEpg(creds: Credentials, streamId: Int, limit: Int = 8): List<EpgProgram> {
        return runCatching {
            val host = normalizeBase(creds.baseUrl)
            val body = getRaw(
                host, creds.username, creds.password, "get_short_epg",
                extra = mapOf("stream_id" to streamId.toString(), "limit" to limit.toString())
            )
            parseEpgListings(body, streamId)
        }.onFailure { Log.w(TAG, "get_short_epg failed for $streamId", it) }.getOrDefault(emptyList())
    }

    fun fetchSimpleEpgTable(creds: Credentials, streamId: Int): List<EpgProgram> {
        return runCatching {
            val host = normalizeBase(creds.baseUrl)
            val body = getRaw(
                host, creds.username, creds.password, "get_simple_data_table",
                extra = mapOf("stream_id" to streamId.toString())
            )
            parseEpgListings(body, streamId)
        }.onFailure { Log.w(TAG, "get_simple_data_table failed for $streamId", it) }.getOrDefault(emptyList())
    }

    fun nowNextFromPrograms(programs: List<EpgProgram>, nowMs: Long = System.currentTimeMillis()): EpgNowNext {
        val sorted = programs.sortedBy { it.startMs }
        val now = sorted.find { it.contains(nowMs) }
        val next = when {
            now != null -> sorted.firstOrNull { it.startMs >= now.endMs }
            else -> sorted.firstOrNull { it.startMs > nowMs }
        }
        return EpgNowNext(now = now, next = next)
    }

    internal fun parseEpgListings(
        body: String,
        streamId: Int = 0,
        displayZone: ZoneId = ZoneId.systemDefault(),
        nowMs: Long = System.currentTimeMillis()
    ): List<EpgProgram> {
        if (body.isBlank() || body == "[]" || body == "{}") return emptyList()
        val trimmed = body.trimStart()
        val listings: List<ShortEpgListing> = try {
            when {
                trimmed.startsWith("[") -> decodeListRecovering(trimmed, "epg")
                trimmed.startsWith("{") -> {
                    val obj = json.parseToJsonElement(trimmed).jsonObject
                    val arr = obj["epg_listings"] ?: obj["listings"] ?: obj.values.firstOrNull { it is JsonArray }
                    if (arr == null) emptyList() else decodeListRecovering(arr.toString(), "epg")
                }
                else -> emptyList()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "EPG parse failed", t)
            emptyList()
        }
        val textZone = providerZone ?: displayZone
        data class Raw(
            val id: String,
            val title: String,
            val description: String?,
            val primary: EpgTime.Instants,
            val localText: EpgTime.Instants
        )
        val rawRows = listings.mapNotNull { listing ->
            val title = decodeMaybeBase64(listing.title).ifBlank { "Program" }
            val desc = listing.description?.let { decodeMaybeBase64(it) }?.takeIf { it.isNotBlank() }
            val startMs = EpgTime.fromFields(
                listing.startTimestamp?.toString(),
                listing.start,
                displayZone,
                providerZone
            )
            val endMs = EpgTime.fromFields(
                (listing.stopTimestamp ?: listing.endTimestamp)?.toString(),
                listing.end,
                displayZone,
                providerZone
            ).takeIf { it > 0L } ?: (startMs + 30 * 60 * 1000L)
            if (startMs <= 0L || endMs <= startMs) return@mapNotNull null
            val localStart = listing.start?.let { EpgTime.fromNaiveInZone(it, textZone) }?.takeIf { it > 0L } ?: startMs
            val localEnd = listing.end?.let { EpgTime.fromNaiveInZone(it, textZone) }?.takeIf { it > 0L } ?: endMs
            Raw(
                id = listing.id?.takeIf { it.isNotBlank() } ?: "$streamId-$startMs",
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
                title = row.title,
                description = row.description,
                startMs = times.startMs,
                endMs = times.endMs,
                id = row.id,
                channelStreamId = streamId
            )
        }.sortedBy { it.startMs }
    }

    internal fun parseServerInfoZone(body: String): ZoneId? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return null
        val info = (root["server_info"] as? JsonObject) ?: root
        val named = info["timezone"]?.jsonPrimitive?.contentOrNull
            ?.let { EpgTime.zoneOrNull(it) }
        if (named != null) return named
        val timeNow = info["time_now"]?.jsonPrimitive?.contentOrNull
        val tsNow = info["timestamp_now"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        return EpgTime.inferProviderZone(timeNow, tsNow)
    }

    private fun decodeMaybeBase64(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return t
        return runCatching {
            val decoded = String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8).trim()
            if (decoded.any { it.code in 1..8 }) t else decoded.ifBlank { t }
        }.getOrDefault(t)
    }

    private fun JsonElement.asString(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        else -> null
    }

    private inline fun <reified T> getListFlexible(
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
        val fixed = if (trimmed.startsWith("[") && !trimmed.endsWith("]")) {
            val lastObj = trimmed.lastIndexOf('}')
            if (lastObj > 0) trimmed.substring(0, lastObj + 1) + "]" else trimmed
        } else trimmed
        return decodeListRecovering(fixed, action)
    }

    /**
     * Decode a JSON array; on corrupt/truncated provider payloads, recover every
     * complete object that still parses (skips bad entries mid-list).
     */
    private inline fun <reified T> decodeListRecovering(raw: String, action: String): List<T> {
        try {
            return json.decodeFromString(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "Parse failed for $action (len=${raw.length}); attempting partial recovery", t)
        }
        val recovered = recoverJsonObjectArray<T>(raw)
        if (recovered.isNotEmpty()) {
            Log.w(TAG, "Recovered ${recovered.size} items from corrupt $action payload")
            return recovered
        }
        Log.e(TAG, "Recovery produced 0 items for $action")
        error("Failed to parse $action (corrupt or truncated JSON)")
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
                                runCatching { json.decodeFromString<T>(slice) }
                                    .onSuccess { out.add(it) }
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
            .header("User-Agent", "TotalIPTVPro/1.1")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Xtream ${action ?: "auth"} failed: HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }


    /** Parse Xtream VOD `added` (unix seconds or ms; string or number JSON). */
    /**
     * Prefer Xtream `rating` as IMDb/TMDB-style /10 number.
     * Fall back to `rating_5based` converted to /10. Skip zeros / junk.
     */
    private fun normalizeRating(rating: String?, rating5based: String?): String? {
        val raw = rating?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("N/A", true) && it != "0" && it != "0.0"
        }
        val asNum = raw?.replace(',', '.')?.toFloatOrNull()
        if (asNum != null) {
            if (asNum <= 0f) return null
            return String.format(Locale.US, "%.1f", asNum)
        }
        val r5 = rating5based?.trim()?.replace(',', '.')?.toFloatOrNull()
        if (r5 != null && r5 > 0f) {
            val outOfTen = if (r5 <= 5.01f) r5 * 2f else r5
            return String.format(Locale.US, "%.1f", outOfTen)
        }
        return null
    }

    private fun parseXtreamAdded(raw: JsonElement?): Long? {
        if (raw == null) return null
        val n = when (raw) {
            is JsonPrimitive -> raw.contentOrNull?.trim()?.toLongOrNull()
                ?: raw.contentOrNull?.trim()?.toDoubleOrNull()?.toLong()
            else -> null
        } ?: return null
        if (n <= 0L) return null
        return if (n < 10_000_000_000L) n * 1000L else n
    }

    private fun normalizeBase(raw: String): String {
        var u = raw.trim().removeSuffix("/")
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            u = "http://$u"
        }
        return u
    }
}
