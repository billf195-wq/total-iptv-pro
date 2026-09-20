package com.totaliptv.pro2.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ResumeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tip2_resume", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class ResumeEntry(
        val key: String,
        val catalogId: String,
        val name: String,
        val kind: String,
        val posterUrl: String? = null,
        val streamUrl: String = "",
        val xtreamStreamId: Int? = null,
        val seriesId: Int? = null,
        val episodeId: String? = null,
        val episodeLabel: String? = null,
        val season: Int? = null,
        val episodeNum: Int? = null,
        val lastOpenedEpochMs: Long = 0L,
        val positionMs: Long? = null
    )

    @Serializable
    private data class ResumeFile(val entries: List<ResumeEntry> = emptyList())

    fun load(): List<ResumeEntry> {
        return try {
            val raw = prefs.getString(KEY, null) ?: return emptyList()
            json.decodeFromString<ResumeFile>(raw).entries.sortedByDescending { it.lastOpenedEpochMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAll(entries: List<ResumeEntry>) {
        val trimmed = entries.sortedByDescending { it.lastOpenedEpochMs }.take(MAX_ENTRIES)
        prefs.edit().putString(KEY, json.encodeToString(ResumeFile(trimmed))).apply()
    }

    fun recordPlay(item: MediaItem, positionMs: Long? = null): List<ResumeEntry> {
        if (item.kind == ContentKind.LIVE) return load()
        if (item.streamUrl.isBlank() && item.kind != ContentKind.SERIES) return load()

        val now = System.currentTimeMillis()
        val isEpisode = item.parentSeriesId != null || item.id.startsWith("ep-")
        val key: String
        val catalogId: String
        val displayName: String
        val kind: String
        val seriesId: Int?
        val episodeId: String?
        val episodeLabel: String?

        when {
            isEpisode || (item.kind == ContentKind.SERIES && item.playable) -> {
                val sid = item.parentSeriesId ?: return load()
                key = "series-$sid"
                catalogId = "series-$sid"
                displayName = item.parentSeriesName?.takeIf { it.isNotBlank() } ?: item.name
                kind = ContentKind.SERIES.name
                seriesId = sid
                episodeId = SeriesPlayback.normalizeEpisodeId(item.id)
                    ?: item.xtreamStreamId?.toString()
                episodeLabel = item.name
            }
            item.kind == ContentKind.VOD -> {
                key = item.id
                catalogId = item.id
                displayName = item.name
                kind = ContentKind.VOD.name
                seriesId = null
                episodeId = null
                episodeLabel = null
            }
            else -> return load()
        }

        val entry = ResumeEntry(
            key = key,
            catalogId = catalogId,
            name = displayName,
            kind = kind,
            posterUrl = item.posterUrl ?: item.logoUrl ?: item.backdropUrl,
            streamUrl = item.streamUrl,
            xtreamStreamId = item.xtreamStreamId,
            seriesId = seriesId,
            episodeId = episodeId,
            episodeLabel = episodeLabel,
            season = item.season,
            episodeNum = item.episodeNum,
            lastOpenedEpochMs = now,
            positionMs = positionMs
        )
        val next = listOf(entry) + load().filterNot { it.key == key }
        saveAll(next)
        return next
    }

    fun forSeries(seriesId: Int?): ResumeEntry? = Companion.forSeries(seriesId, load())

    companion object {
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 40

        fun forSeries(seriesId: Int?, entries: List<ResumeEntry>): ResumeEntry? {
            if (seriesId == null) return null
            val key = "series-$seriesId"
            return entries.firstOrNull { it.seriesId == seriesId || it.key == key || it.catalogId == key }
        }
    }
}
