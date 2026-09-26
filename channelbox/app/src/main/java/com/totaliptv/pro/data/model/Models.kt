package com.totaliptv.pro.data.model

import kotlinx.serialization.Serializable

enum class SourceType {
    M3U,
    XTREAM
}

@Serializable
data class PlaylistSource(
    val id: String,
    val name: String,
    val type: SourceType,
    val m3uUrl: String? = null,
    val xtreamBaseUrl: String? = null,
    val xtreamUsername: String? = null,
    val xtreamPassword: String? = null
)

@Serializable
data class Category(
    val id: String,
    val name: String,
    val kind: ContentKind
)

@Serializable
enum class ContentKind {
    LIVE,
    VOD,
    SERIES
}

@Serializable
data class MediaItem(
    val id: String,
    val name: String,
    val streamUrl: String,
    val categoryId: String?,
    val kind: ContentKind,
    val logoUrl: String? = null,
    val posterUrl: String? = null,
    val groupTitle: String? = null,
    val epgChannelId: String? = null,
    val xtreamStreamId: Int? = null,
    /** Xtream VOD/live `added` as epoch ms when known; used for Newly added sorting. */
    val addedMs: Long? = null,
    /**
     * Xtream bouquet / channel number (`num`). Used only for ordering, never for playback.
     * Null/0 means unknown (keep catalog order among unnumbered rows).
     */
    val channelNum: Int? = null,
    /** All catalog category ids this live channel belongs to (`live-12`, …). */
    val categoryIds: List<String> = emptyList(),
    /** Display rating for posters (e.g. "8.2"); null/blank = hide badge. */
    val rating: String? = null,
    /** Xtream `tmdb` / `tmdb_id` when the provider sent one. */
    val tmdbId: String? = null,
    /** Xtream youtube_trailer — video id or URL; null = no preview. */
    val youtubeTrailer: String? = null,
    /** Plot / synopsis from get_vod_info / get_series_info when resolved. */
    val plot: String? = null
) {
    /** Best available artwork for browse grids / logos. */
    fun artworkUrl(): String? = posterUrl?.takeIf { it.isNotBlank() } ?: logoUrl?.takeIf { it.isNotBlank() }

    /** Numeric-style rating for UI badge; never returns "0.0". */
    fun displayRating(): String? =
        rating?.trim()?.takeIf { it.isNotBlank() && it != "0" && it != "0.0" && !it.equals("N/A", true) }
}

@Serializable
data class FavoriteRef(
    val id: String,
    val name: String,
    val streamUrl: String,
    val kind: ContentKind,
    val logoUrl: String? = null
)

@Serializable
data class EpgProgram(
    val title: String,
    val description: String? = null,
    val startMs: Long,
    val endMs: Long,
    val id: String = "",
    /** Xtream stream_id this row was fetched for; 0 when unknown. */
    val channelStreamId: Int = 0
) {
    fun contains(nowMs: Long): Boolean = nowMs in startMs until endMs
}

@Serializable
data class EpgNowNext(
    val now: EpgProgram? = null,
    val next: EpgProgram? = null
)

@Serializable
data class EpgChannelRow(
    val channel: MediaItem,
    val programs: List<EpgProgram> = emptyList(),
    val nowNext: EpgNowNext = EpgNowNext()
)

@Serializable
data class WatchProgress(
    val id: String,
    val positionMs: Long,
    val durationMs: Long,
    val title: String,
    val kind: ContentKind,
    val streamUrl: String,
    val logoUrl: String? = null,
    val updatedAtMs: Long = 0L,
    /** Catalog id (e.g. series-123) when id is an episode leaf (series-ep-…). */
    val catalogId: String? = null
) {
    fun fraction(): Float =
        if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    /** True when we should seek on open (not near start/end). */
    fun shouldResume(
        minPositionMs: Long = 15_000L,
        completeFraction: Float = 0.92f,
        nearEndMs: Long = 30_000L
    ): Boolean {
        if (positionMs < minPositionMs) return false
        if (durationMs > 0L) {
            if (positionMs.toFloat() / durationMs.toFloat() >= completeFraction) return false
            if (durationMs - positionMs <= nearEndMs) return false
        }
        return true
    }

    fun isCompleted(
        completeFraction: Float = 0.92f,
        nearEndMs: Long = 30_000L
    ): Boolean {
        if (durationMs <= 0L) return false
        if (positionMs.toFloat() / durationMs.toFloat() >= completeFraction) return true
        if (durationMs - positionMs <= nearEndMs) return true
        return false
    }

    fun toMediaItem(): MediaItem = MediaItem(
        id = id,
        name = title,
        streamUrl = streamUrl,
        categoryId = null,
        kind = kind,
        logoUrl = logoUrl,
        posterUrl = logoUrl
    )
}

