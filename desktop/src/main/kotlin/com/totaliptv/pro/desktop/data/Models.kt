package com.totaliptv.pro.desktop.data

import kotlinx.serialization.Serializable

enum class SourceType { M3U, XTREAM }

enum class ContentKind { LIVE, VOD, SERIES }

@Serializable
data class SavedPrefs(
    val sourceType: String = SourceType.XTREAM.name,
    val m3uUrl: String = "",
    val xtreamBaseUrl: String = "",
    val xtreamUsername: String = "",
    val xtreamPassword: String = "",
    val onboarded: Boolean = false,
    /** auto | vlc | mpv | ffplay */
    val preferredPlayer: String = "auto",
    /** dark | light */
    val themeMode: String = "dark",
    /** Poster grid columns for Movies/Series (5, 6, or 11). Default 11. */
    val posterColumns: Int = 11,
    /** Movies/Series browse sort: AZ | ZA | RECENT */
    val browseSort: String = "AZ",
    /**
     * HTTP shelf base for in-app desktop updates (version.json + tarball).
     * Keep separate from Xtream login. Editable in Settings.
     */
    val updateShelfUrl: String = "https://github.com/billf195-wq/total-iptv-pro/releases/latest",
    /**
     * TV Guide layout: "current" (timeline + detail) or "classic" (channel list + schedule list).
     */
    val guideStyle: String = "current",
    val windowWidth: Int = 1280,
    val windowHeight: Int = 800,
    val windowX: Int? = null,
    val windowY: Int? = null,
    val windowMaximized: Boolean = false,
    /**
     * Extra hours added to guide times. 0 = Auto (existing OS / provider clock, no shift).
     */
    val epgTimeOffsetHours: Int = 0,
    /**
     * Optional recordings folder on **this** machine only. Blank = OS default
     * (Windows %LOCALAPPDATA%\TotalIptvPro\Recordings, Linux ~/Videos/TotalIptvPro/Recordings).
     */
    val recordingsDir: String = ""
)

data class Category(
    val id: String,
    val name: String,
    val kind: ContentKind
)

data class MediaItem(
    val id: String,
    val name: String,
    val streamUrl: String,
    val categoryId: String?,
    val kind: ContentKind,
    val logoUrl: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val groupTitle: String? = null,
    val xtreamStreamId: Int? = null,
    /**
     * Xtream bouquet / channel number (`num`). Used only for ordering, never for playback.
     * 0 means unknown (keep catalog order among unnumbered rows).
     */
    val channelNum: Int = 0,
    /** Xtream / XMLTV `epg_channel_id` (or M3U `tvg-id`). Identity only — not a stream id. */
    val epgChannelId: String? = null,
    /** All catalog category ids this live channel belongs to (`live-12`, …). */
    val categoryIds: List<String> = emptyList(),
    /** False for series titles (open episodes); true for live/VOD/episodes. */
    val playable: Boolean = true,
    /** Unix epoch seconds from Xtream `added` / `last_modified` (0 if unknown). */
    val addedEpoch: Long = 0L,
    /** Xtream rating string (e.g. "7.8") when present. */
    val rating: String? = null,
    /** Xtream 0–5 scale rating when present. */
    val rating5Based: Double? = null,
    val country: String? = null,
    /** Release year when known (Xtream `year` or parsed from title). */
    val year: Int? = null,
    val plot: String? = null,
    val genre: String? = null,
    /** Cast / actors string from Xtream info (comma-separated when available). */
    val cast: String? = null,
    /** Parent series id when this item is an episode (for resume / continue watching). */
    val parentSeriesId: Int? = null,
    val parentSeriesName: String? = null,
    val season: Int? = null,
    val episodeNum: Int? = null
) {
    fun artworkUrl(): String? =
        posterUrl?.takeIf { it.isNotBlank() }
            ?: logoUrl?.takeIf { it.isNotBlank() }
            ?: backdropUrl?.takeIf { it.isNotBlank() }

    /** Best-effort numeric rating for sorting (higher is better). */
    fun ratingScore(): Double {
        rating?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { return it }
        rating5Based?.takeIf { it > 0.0 }?.let { return it * 2.0 } // map 0–5 → ~0–10
        return 0.0
    }
}

data class SeriesEpisode(
    val id: String,
    val title: String,
    val season: Int,
    val episodeNum: Int,
    val streamUrl: String,
    val containerExtension: String = "mp4",
    val posterUrl: String? = null
) {
    fun toMediaItem(seriesName: String, seriesId: Int? = null): MediaItem = MediaItem(
        id = "ep-$id",
        name = if (title.isNotBlank() && title != "Episode $episodeNum") {
            "S${season}E${episodeNum}: $title"
        } else {
            "$seriesName — S${season}E${episodeNum}"
        },
        streamUrl = streamUrl,
        categoryId = null,
        kind = ContentKind.SERIES,
        posterUrl = posterUrl,
        groupTitle = "Season $season",
        xtreamStreamId = id.toIntOrNull(),
        playable = true,
        parentSeriesId = seriesId,
        parentSeriesName = seriesName,
        season = season,
        episodeNum = episodeNum
    )
}

data class SeriesDetail(
    val seriesId: Int,
    val name: String,
    val plot: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val cast: String? = null,
    val rating: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val episodes: List<SeriesEpisode> = emptyList()
)

/** VOD / movie detail from Xtream get_vod_info (description + cast before play). */
data class VodDetail(
    val streamId: Int,
    val name: String,
    val plot: String? = null,
    val cast: String? = null,
    val rating: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val streamUrl: String = "",
    val catalogId: String = "",
    val categoryId: String? = null
) {
    fun toMediaItem(): MediaItem = MediaItem(
        id = catalogId.ifBlank { "vod-$streamId" },
        name = name,
        streamUrl = streamUrl,
        categoryId = categoryId,
        kind = ContentKind.VOD,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        xtreamStreamId = streamId,
        playable = streamUrl.isNotBlank(),
        rating = rating,
        year = year,
        plot = plot,
        genre = genre,
        cast = cast
    )
}

data class Catalog(
    val liveCategories: List<Category> = emptyList(),
    val vodCategories: List<Category> = emptyList(),
    val seriesCategories: List<Category> = emptyList(),
    val liveItems: List<MediaItem> = emptyList(),
    val vodItems: List<MediaItem> = emptyList(),
    val seriesItems: List<MediaItem> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** `url-tvg` / `x-tvg-url` from an M3U header, when the playlist ships XMLTV. */
    val xmltvUrl: String? = null
)

/** One EPG program listing for a live channel. */
data class EpgProgram(
    val id: String,
    val title: String,
    val description: String? = null,
    val startMs: Long,
    val endMs: Long,
    val channelStreamId: Int
) {
    fun contains(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs in startMs until endMs
}

data class ChannelEpg(
    val streamId: Int,
    val programs: List<EpgProgram> = emptyList()
)
