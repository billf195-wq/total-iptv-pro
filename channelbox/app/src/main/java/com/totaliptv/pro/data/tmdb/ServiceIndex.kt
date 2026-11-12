package com.totaliptv.pro.data.tmdb

import com.totaliptv.pro.data.Ratings
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
    /** [StreamingService.key] values. Empty for ratings-only (top-rated) entries. */
    val services: List<String> = emptyList(),
    /** TMDB vote_average on the 0–10 scale, when the discover page sent one. */
    val voteAverage: Double? = null,
    val voteCount: Int? = null
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
        /** Bumped when Hulu / Disney+ / Paramount+ queries were added so old caches refetch. */
        const val CACHE_VERSION = 3
    }
}

/**
 * In-memory lookup: catalog item -> streaming services and/or TMDB rating, by TMDB id when
 * the provider sent one, else by normalized title + year (±1; short/colon keys need the
 * same year). Built once per cache load.
 *
 * Ratings-only entries (no service) never appear in [lookup]; service lookup is unchanged.
 */
class ServiceIndex(entries: List<TmdbServiceEntry>) {
    /** [exact]: full-title key; false for the pre-colon short form (needs the same year). */
    private class Hit(val year: Int?, val exact: Boolean, val services: Set<StreamingService>)
    private class RatingHit(val year: Int?, val exact: Boolean, val average: Double, val voteCount: Int)

    private val byIdMovie = HashMap<Int, Set<StreamingService>>()
    private val byIdTv = HashMap<Int, Set<StreamingService>>()
    private val byKeyMovie = HashMap<String, MutableList<Hit>>()
    private val byKeyTv = HashMap<String, MutableList<Hit>>()

    private val ratingByIdMovie = HashMap<Int, RatingHit>()
    private val ratingByIdTv = HashMap<Int, RatingHit>()
    private val ratingByKeyMovie = HashMap<String, MutableList<RatingHit>>()
    private val ratingByKeyTv = HashMap<String, MutableList<RatingHit>>()

    /** Distinct TMDB titles that belong to at least one streaming service. */
    val size: Int
    /** Distinct TMDB titles with a usable rating (vote_count >= [Ratings.TMDB_MIN_VOTE_COUNT]). */
    val ratedSize: Int

    init {
        // Merge duplicates (a title found under several services / queries).
        val merged = LinkedHashMap<String, TmdbServiceEntry>()
        for (e in entries) {
            val k = "${e.type}:${e.id}"
            val prev = merged[k]
            merged[k] = if (prev == null) e else mergeEntries(prev, e)
        }
        var serviceCount = 0
        var ratedCount = 0
        for (e in merged.values) {
            val services = e.services.mapNotNull { key -> StreamingService.entries.firstOrNull { it.key == key } }.toSet()
            val tv = e.type == "tv"
            if (services.isNotEmpty()) {
                serviceCount++
                (if (tv) byIdTv else byIdMovie)[e.id] = services
                val map = if (tv) byKeyTv else byKeyMovie
                for (key in TitleMatch.exactKeysForTmdb(e.title, e.originalTitle)) {
                    map.getOrPut(key) { mutableListOf() } += Hit(e.year, exact = true, services)
                }
                for (key in TitleMatch.shortKeysForTmdb(e.title, e.originalTitle)) {
                    map.getOrPut(key) { mutableListOf() } += Hit(e.year, exact = false, services)
                }
            }
            val score = Ratings.tmdbScore(e.voteAverage, e.voteCount) ?: continue
            ratedCount++
            val votes = e.voteCount ?: 0
            val idHit = RatingHit(e.year, exact = true, score, votes)
            (if (tv) ratingByIdTv else ratingByIdMovie)[e.id] = idHit
            val rmap = if (tv) ratingByKeyTv else ratingByKeyMovie
            for (key in TitleMatch.exactKeysForTmdb(e.title, e.originalTitle)) {
                rmap.getOrPut(key) { mutableListOf() } += RatingHit(e.year, exact = true, score, votes)
            }
            for (key in TitleMatch.shortKeysForTmdb(e.title, e.originalTitle)) {
                rmap.getOrPut(key) { mutableListOf() } += RatingHit(e.year, exact = false, score, votes)
            }
        }
        size = serviceCount
        ratedSize = ratedCount
    }

    fun isEmpty(): Boolean = size == 0

    fun lookup(item: MediaItem): Set<StreamingService> {
        val tv = isTv(item) ?: return emptySet()
        item.tmdbId?.takeIf { it > 0 }?.let { id ->
            (if (tv) byIdTv else byIdMovie)[id]?.let { return it }
        }
        val key = TitleMatch.normalize(item.name)
        if (key.isEmpty()) return emptySet()
        val hits = (if (tv) byKeyTv else byKeyMovie)[key] ?: return emptySet()
        val matched = yearMatches(item, hits, yearOf = { it.year }, exactOf = { it.exact })
        if (matched.isEmpty()) return emptySet()
        return matched.flatMapTo(LinkedHashSet()) { it.services }
    }

    /**
     * TMDB vote average (0–10) when the title matched and cleared the vote-count
     * threshold; null otherwise. Does not consider the provider rating.
     */
    fun ratingScore(item: MediaItem): Double? {
        val hit = ratingHit(item) ?: return null
        return hit.average
    }

    /** "8.2"-style text for [ratingScore]; null when there is no usable TMDB rating. */
    fun ratingDisplay(item: MediaItem): String? = Ratings.formatTen(ratingScore(item))

    private fun ratingHit(item: MediaItem): RatingHit? {
        val tv = isTv(item) ?: return null
        item.tmdbId?.takeIf { it > 0 }?.let { id ->
            (if (tv) ratingByIdTv else ratingByIdMovie)[id]?.let { return it }
        }
        val key = TitleMatch.normalize(item.name)
        if (key.isEmpty()) return null
        val hits = (if (tv) ratingByKeyTv else ratingByKeyMovie)[key] ?: return null
        val matched = yearMatches(item, hits, yearOf = { it.year }, exactOf = { it.exact })
        if (matched.isEmpty()) return null
        return matched.maxWithOrNull(compareBy<RatingHit> { it.voteCount }.thenBy { it.average })
    }

    companion object {
        private fun isTv(item: MediaItem): Boolean? = when (item.kind) {
            ContentKind.SERIES -> true
            ContentKind.VOD -> false
            else -> null
        }

        /** Prefer the copy with more votes when the same title is seen twice. */
        internal fun mergeEntries(prev: TmdbServiceEntry, e: TmdbServiceEntry): TmdbServiceEntry {
            val services = (prev.services + e.services).distinct()
            val prevVotes = prev.voteCount ?: -1
            val newVotes = e.voteCount ?: -1
            return if (newVotes > prevVotes) {
                e.copy(services = services)
            } else {
                prev.copy(services = services)
            }
        }

        /**
         * Exact keys may be ±1 year; colon/short keys must be the same year.
         * With no provider year, only a unique exact (full-title) year is trusted.
         */
        internal fun <T> yearMatches(
            item: MediaItem,
            hits: List<T>,
            yearOf: (T) -> Int?,
            exactOf: (T) -> Boolean
        ): List<T> {
            val year = item.year ?: TitleMatch.extractYear(item.name)
            return if (year == null) {
                val exact = hits.filter { exactOf(it) }
                if (exact.isNotEmpty() && exact.map { yearOf(it) }.distinct().size == 1) exact else emptyList()
            } else {
                hits.filter { h ->
                    val hy = yearOf(h)
                    if (exactOf(h)) hy == null || kotlin.math.abs(hy - year) <= 1
                    else hy == year
                }
            }
        }
    }
}
