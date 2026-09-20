package com.totaliptv.pro.desktop.data

import com.totaliptv.pro.desktop.util.AppPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Local continue-watching / recently-played store under the OS config dir.
 * External players usually cannot report scrub position, so we persist last-opened
 * (and optional positionMs when available).
 */
object ResumeStore {
    private const val MAX_ENTRIES = 40

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val dir: Path = AppPaths.configDir
    private val file: Path = dir.resolve("resume.json")
    private val tmp: Path = dir.resolve("resume.json.tmp")

    @Serializable
    data class ResumeEntry(
        /** Stable key: vod-{id} or series-{seriesId} (one card per title). */
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
    data class ResumeFile(
        val entries: List<ResumeEntry> = emptyList()
    )

    fun load(): List<ResumeEntry> {
        return try {
            if (!file.exists()) return emptyList()
            json.decodeFromString<ResumeFile>(file.readText()).entries
                .sortedByDescending { it.lastOpenedEpochMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAll(entries: List<ResumeEntry>) {
        Files.createDirectories(dir)
        val trimmed = entries
            .sortedByDescending { it.lastOpenedEpochMs }
            .take(MAX_ENTRIES)
        tmp.writeText(json.encodeToString(ResumeFile(trimmed)))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Record a VOD movie or series episode as recently played. Live TV is ignored. */
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

        if (isEpisode || (item.kind == ContentKind.SERIES && item.playable)) {
            // Never fall back to the episode stream id — that would store series-{episodeId}
            // and break lookup when reopening the real series poster.
            val sid = item.parentSeriesId ?: return load()
            key = "series-$sid"
            catalogId = "series-$sid"
            displayName = item.parentSeriesName?.takeIf { it.isNotBlank() } ?: item.name
            kind = ContentKind.SERIES.name
            seriesId = sid
            episodeId = SeriesPlayback.normalizeEpisodeId(item.id)
                ?: item.xtreamStreamId?.toString()
            episodeLabel = item.name
        } else if (item.kind == ContentKind.VOD) {
            key = item.id
            catalogId = item.id
            displayName = item.name
            kind = ContentKind.VOD.name
            seriesId = null
            episodeId = null
            episodeLabel = null
        } else if (item.kind == ContentKind.SERIES && !item.playable) {
            // Opening series detail without playing — do not resume-track
            return load()
        } else {
            return load()
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
        val rest = load().filterNot { it.key == key }
        val next = listOf(entry) + rest
        saveAll(next)
        return next
    }

    fun forSeries(seriesId: Int?, entries: List<ResumeEntry> = load()): ResumeEntry? {
        if (seriesId == null) return null
        val key = "series-$seriesId"
        return entries.firstOrNull { it.seriesId == seriesId || it.key == key || it.catalogId == key }
    }

    private fun Path.writeText(text: String) {
        Files.writeString(this, text)
    }
}
