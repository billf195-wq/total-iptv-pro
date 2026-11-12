package com.totaliptv.pro.data.tmdb

import com.totaliptv.pro.data.StreamingService
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import kotlinx.serialization.Serializable

/** One TMDB discover result with the services it was found under. */
@Serializable
data class TmdbServiceEntry(
    /** "movie" or "tv". */
    val type: String,
    val id: Int,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    /** [StreamingService.key] values. */
    val services: List<String> = emptyList()
)

@Serializable
data class TmdbServiceCache(
    val version: Int = CACHE_VERSION,
    val fetchedAtMs: Long = 0L,
    /** Short fingerprint of the key used, so a new key refetches. Never the key itself. */
    val keyFingerprint: String = "",
    val entries: List<TmdbServiceEntry> = emptyList()
) {
    companion object {
        const val CACHE_VERSION = 1
    }
}

/**
 * In-memory lookup: catalog item -> streaming services, by TMDB id when the provider sent one,
 * else by normalized title + year (±1). Built once per cache load.
 */
class ServiceIndex(entries: List<TmdbServiceEntry>) {
    /** [exact]: full-title key; false for the pre-colon short form (needs the same year). */
    private class Hit(val year: Int?, val services: Set<StreamingService>, val exact: Boolean)

    private val byIdMovie = HashMap<Int, Set<StreamingService>>()
    private val byIdTv = HashMap<Int, Set<StreamingService>>()
    private val byKeyMovie = HashMap<String, MutableList<Hit>>()
    private val byKeyTv = HashMap<String, MutableList<Hit>>()

    val size: Int

    init {
        // Merge duplicates (a title found under several services / queries).
        val merged = LinkedHashMap<String, TmdbServiceEntry>()
        for (e in entries) {
            val k = "${e.type}:${e.id}"
            val prev = merged[k]
            merged[k] = if (prev == null) e else prev.copy(services = (prev.services + e.services).distinct())
        }
        for (e in merged.values) {
            val services = e.services.mapNotNull { key -> StreamingService.entries.firstOrNull { it.key == key } }.toSet()
            if (services.isEmpty()) continue
            val tv = e.type == "tv"
            (if (tv) byIdTv else byIdMovie)[e.id] = services
            val map = if (tv) byKeyTv else byKeyMovie
            for (key in TitleMatch.exactKeysForTmdb(e.title, e.originalTitle)) {
                map.getOrPut(key) { mutableListOf() } += Hit(e.year, services, exact = true)
            }
            for (key in TitleMatch.shortKeysForTmdb(e.title, e.originalTitle)) {
                map.getOrPut(key) { mutableListOf() } += Hit(e.year, services, exact = false)
            }
        }
        size = merged.size
    }

    fun isEmpty(): Boolean = size == 0

    fun lookup(item: MediaItem): Set<StreamingService> {
        val tv = when (item.kind) {
            ContentKind.SERIES -> true
            ContentKind.VOD -> false
            else -> return emptySet()
        }
        item.tmdbId?.takeIf { it > 0 }?.let { id ->
            (if (tv) byIdTv else byIdMovie)[id]?.let { return it }
        }
        val key = TitleMatch.normalize(item.name)
        if (key.isEmpty()) return emptySet()
        val hits = (if (tv) byKeyTv else byKeyMovie)[key] ?: return emptySet()
        val year = item.year ?: TitleMatch.extractYear(item.name)
        val matched = if (year == null) {
            // No year from the provider: only trust a full-title key that maps to one title-year.
            val exact = hits.filter { it.exact }
            if (exact.isNotEmpty() && exact.map { it.year }.distinct().size == 1) exact else emptyList()
        } else {
            hits.filter { h ->
                if (h.exact) h.year == null || kotlin.math.abs(h.year - year) <= 1
                else h.year == year
            }
        }
        if (matched.isEmpty()) return emptySet()
        return matched.flatMapTo(LinkedHashSet()) { it.services }
    }
}
