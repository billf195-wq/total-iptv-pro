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
    private const val MAX_ENTRIES = 80

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
        val positionMs: Long? = null,
        val durationMs: Long? = null,
        val playbackPercent: Int? = null
    ) {
        val hasProgress: Boolean
            get() = (playbackPercent != null && playbackPercent in 1..94) ||
                (positionMs != null && durationMs != null && durationMs > 0 &&
                    ((positionMs * 100) / durationMs) in 1..94)

        val progressPercent: Int
            get() = playbackPercent?.takeIf { it in 1..94 }
                ?: if (positionMs != null && durationMs != null && durationMs > 0) {
                    ((positionMs.toDouble() / durationMs.toDouble()) * 100).toInt().coerceIn(0, 100)
                } else 0
    }

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

    /**
     * Continue-watching card is still one per series. Playback position is stored
     * on a separate per-episode key so episode 2 does not inherit episode 1's spot.
     */
    fun episodeProgressKey(seriesId: Int?, episodeId: String?): String? {
        if (seriesId == null || episodeId.isNullOrBlank()) return null
        return "series-$seriesId-ep-${episodeId.trim()}"
    }

    fun progressKeyFor(item: MediaItem): String? {
        if (item.kind == ContentKind.LIVE) return null
        val episode = item.parentSeriesId != null || item.id.startsWith("ep-") ||
            (item.kind == ContentKind.SERIES && item.playable)
        if (episode) {
            val sid = item.parentSeriesId ?: return null
            val ep = SeriesPlayback.normalizeEpisodeId(item.id)
                ?: item.xtreamStreamId?.toString()
                ?: return null
            return episodeProgressKey(sid, ep)
        }
        if (item.kind == ContentKind.VOD) return item.id
        return null
    }

    /** Record a VOD movie or series episode as recently played. Live TV is ignored. */
    fun recordPlay(
        item: MediaItem,
        positionMs: Long? = null,
        durationMs: Long? = null,
        percent: Int? = null
    ): List<ResumeEntry> {
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

        val episodeKey = if (seriesId != null) episodeProgressKey(seriesId, episodeId) else key
        val prior = load()
        val existingEpisode = episodeKey?.let { ek -> prior.firstOrNull { it.key == ek } }
        val effectivePos = positionMs ?: existingEpisode?.positionMs
        val effectiveDur = durationMs ?: existingEpisode?.durationMs
        val effectivePct = percent ?: percentOf(effectivePos, effectiveDur) ?: existingEpisode?.playbackPercent

        val summary = ResumeEntry(
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
            positionMs = effectivePos,
            durationMs = effectiveDur,
            playbackPercent = effectivePct
        )
        val episodeRow = if (episodeKey != null && episodeKey != key) {
            summary.copy(key = episodeKey, catalogId = episodeKey)
        } else {
            null
        }
        val drop = setOfNotNull(key, episodeKey)
        val rest = prior.filterNot { it.key in drop }
        val next = listOfNotNull(summary, episodeRow) + rest
        saveAll(next)
        return next
    }

    fun updateProgress(
        key: String,
        positionMs: Long,
        durationMs: Long? = null,
        percent: Int? = null
    ): List<ResumeEntry> {
        val current = load()
        val index = current.indexOfFirst { it.key == key }
        if (index < 0) return current
        val existing = current[index]
        val computed = percent ?: percentOf(positionMs, durationMs ?: existing.durationMs) ?: existing.playbackPercent
        val updated = existing.copy(
            positionMs = positionMs,
            durationMs = durationMs ?: existing.durationMs,
            playbackPercent = computed,
            lastOpenedEpochMs = System.currentTimeMillis()
        )
        val next = current.toMutableList()
        next[index] = updated
        if (existing.seriesId != null && !existing.episodeId.isNullOrBlank()) {
            val summaryKey = "series-${existing.seriesId}"
            val summaryIdx = next.indexOfFirst {
                it.key == summaryKey && it.episodeId == existing.episodeId
            }
            if (summaryIdx >= 0) {
                next[summaryIdx] = next[summaryIdx].copy(
                    positionMs = updated.positionMs,
                    durationMs = updated.durationMs,
                    playbackPercent = updated.playbackPercent,
                    lastOpenedEpochMs = updated.lastOpenedEpochMs,
                    streamUrl = updated.streamUrl.ifBlank { next[summaryIdx].streamUrl }
                )
            }
        }
        saveAll(next)
        return next
    }

    fun forSeries(seriesId: Int?, entries: List<ResumeEntry> = load()): ResumeEntry? {
        if (seriesId == null) return null
        val key = "series-$seriesId"
        return entries.firstOrNull { it.key == key }
    }

    fun progressForEpisode(
        seriesId: Int?,
        episodeId: String?,
        entries: List<ResumeEntry> = load()
    ): ResumeEntry? {
        val key = episodeProgressKey(seriesId, episodeId) ?: return null
        return entries.firstOrNull { it.key == key }
    }

    fun forVod(streamId: Int?, catalogId: String? = null, entries: List<ResumeEntry> = load()): ResumeEntry? {
        return entries.firstOrNull {
            (!catalogId.isNullOrBlank() && (it.key == catalogId || it.catalogId == catalogId)) ||
                (streamId != null && it.xtreamStreamId == streamId && it.kind == ContentKind.VOD.name && !it.key.contains("-ep-"))
        }
    }

    private fun percentOf(positionMs: Long?, durationMs: Long?): Int? {
        if (positionMs == null || durationMs == null || durationMs <= 0L) return null
        return ((positionMs.toDouble() / durationMs.toDouble()) * 100).toInt().coerceIn(0, 100)
    }

    private fun Path.writeText(text: String) {
        Files.writeString(this, text)
    }
}
