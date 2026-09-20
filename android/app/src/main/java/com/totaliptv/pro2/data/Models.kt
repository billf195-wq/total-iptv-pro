package com.totaliptv.pro2.data

import kotlinx.serialization.Serializable

enum class SourceType { XTREAM }

enum class ContentKind { LIVE, VOD, SERIES }

@Serializable
data class SavedPrefs(
    val sourceType: String = SourceType.XTREAM.name,
    val xtreamBaseUrl: String = "",
    val xtreamUsername: String = "",
    val xtreamPassword: String = "",
    val onboarded: Boolean = false,
    val themeMode: String = "dark",
    val posterColumns: Int = 6,
    val browseSort: String = "AZ",
    /** HTTP shelf for Shield / phone in-app APK updates (version-pro2.json). */
    val updateShelfUrl: String = "http://192.168.4.39:8766/"
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
    val playable: Boolean = true,
    val addedEpoch: Long = 0L,
    val rating: String? = null,
    val rating5Based: Double? = null,
    val country: String? = null,
    val year: Int? = null,
    val plot: String? = null,
    val genre: String? = null,
    val parentSeriesId: Int? = null,
    val parentSeriesName: String? = null,
    val season: Int? = null,
    val episodeNum: Int? = null
) {
    fun artworkUrl(): String? =
        posterUrl?.takeIf { it.isNotBlank() }
            ?: logoUrl?.takeIf { it.isNotBlank() }
            ?: backdropUrl?.takeIf { it.isNotBlank() }

    fun ratingScore(): Double {
        rating?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { return it }
        rating5Based?.takeIf { it > 0.0 }?.let { return it * 2.0 }
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
    val episodes: List<SeriesEpisode> = emptyList()
)

data class Catalog(
    val liveCategories: List<Category> = emptyList(),
    val vodCategories: List<Category> = emptyList(),
    val seriesCategories: List<Category> = emptyList(),
    val liveItems: List<MediaItem> = emptyList(),
    val vodItems: List<MediaItem> = emptyList(),
    val seriesItems: List<MediaItem> = emptyList(),
    val warnings: List<String> = emptyList()
)

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
