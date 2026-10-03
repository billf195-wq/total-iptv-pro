package com.totaliptv.pro.desktop.ui

import com.totaliptv.pro.desktop.artwork.TmdbTitle
import com.totaliptv.pro.desktop.data.ContentKind
import com.totaliptv.pro.desktop.data.MediaItem
import com.totaliptv.pro.desktop.data.ResumeStore

/**
 * Collapses provider copies of one movie or series on Home.
 * Copies share a resolved TMDB id when one is known, otherwise the normalized
 * title plus year. The copy kept is the one last watched, then the highest
 * quality, then the earliest in the catalog.
 */
object HomeDedupe {
    private val uhd = Regex("""(?i)(?<![a-z0-9])(?:2160p|4320p|4k|uhd)(?![a-z0-9])""")
    private val fhd = Regex("""(?i)(?<![a-z0-9])(?:1080p|fhd)(?![a-z0-9])""")
    private val hd = Regex("""(?i)(?<![a-z0-9])(?:720p|hd)(?![a-z0-9])""")
    private val sd = Regex("""(?i)(?<![a-z0-9])(?:576p|480p|sd)(?![a-z0-9])""")

    /** 4K, then 1080p/FHD, then 720p/HD, then SD. Untagged names score 0. */
    fun qualityScore(name: String): Int = when {
        uhd.containsMatchIn(name) -> 4
        fhd.containsMatchIn(name) -> 3
        hd.containsMatchIn(name) -> 2
        sd.containsMatchIn(name) -> 1
        else -> 0
    }

    fun providerTmdbId(item: MediaItem): String? =
        item.tmdbId?.trim()?.takeIf { it.isNotEmpty() && it != "0" }

    /**
     * One title per row. [items] order is the row order (rating or recency).
     * [catalogIndex] is the original list position so "first listed" is the
     * catalog, not whichever copy sorted higher.
     */
    fun dedupe(
        items: List<MediaItem>,
        catalogIndex: Map<String, Int> = items.withIndex().associate { it.value.id to it.index },
        resume: List<ResumeStore.ResumeEntry> = emptyList(),
        shownElsewhere: List<MediaItem> = emptyList(),
        resolvedId: (MediaItem) -> String? = ::providerTmdbId
    ): List<MediaItem> {
        if (items.size <= 1 && shownElsewhere.isEmpty()) return items
        val watched = watchIndex(resume)
        val parent = IntArray(items.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }
        val byTmdb = HashMap<String, Int>()
        val byTitle = HashMap<String, Int>()
        for (i in items.indices) {
            val item = items[i]
            val id = resolvedId(item)
            if (!id.isNullOrBlank()) {
                val key = "tmdb:${item.kind}:$id"
                val prev = byTmdb.putIfAbsent(key, i)
                if (prev != null) union(prev, i)
            }
            val title = TmdbTitle.signature(item)
            if (title.isNotBlank()) {
                val key = "title:${item.kind}:$title"
                val prev = byTitle.putIfAbsent(key, i)
                if (prev != null) union(prev, i)
            }
        }
        val blocked = shownElsewhere.flatMapTo(HashSet()) { keysOf(it, resolvedId) }
        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (i in items.indices) {
            groups.getOrPut(find(i)) { mutableListOf() }.add(i)
        }
        val out = ArrayList<MediaItem>(groups.size)
        for (members in groups.values) {
            if (members.any { keysOf(items[it], resolvedId).any { key -> key in blocked } }) continue
            var best = items[members[0]]
            for (index in 1 until members.size) {
                best = prefer(best, items[members[index]], watched, catalogIndex)
            }
            out += best
        }
        return out
    }

    fun dedupeContinue(
        pairs: List<Pair<ResumeStore.ResumeEntry, MediaItem?>>
    ): List<Pair<ResumeStore.ResumeEntry, MediaItem?>> {
        if (pairs.size <= 1) return pairs
        val resume = pairs.map { it.first }
        val items = pairs.map { (entry, media) -> media ?: placeholder(entry) }
        val kept = dedupe(items, resume = resume).map { it.id }.toSet()
        val seen = HashSet<String>()
        return pairs.filter { (entry, media) ->
            val id = media?.id ?: entry.catalogId.ifBlank { entry.key }
            id in kept && seen.add(id)
        }
    }

    /** Same id twice in a Movies or Series grid. Different copies stay. */
    fun dropIdenticalIds(items: List<MediaItem>): List<MediaItem> {
        if (items.size <= 1) return items
        val seen = HashSet<String>(items.size)
        val out = ArrayList<MediaItem>(items.size)
        for (item in items) {
            if (seen.add(item.id)) out += item
        }
        return if (out.size == items.size) items else out
    }

    fun placeholder(entry: ResumeStore.ResumeEntry): MediaItem = MediaItem(
        id = entry.catalogId.ifBlank { entry.key },
        name = entry.name,
        streamUrl = entry.streamUrl,
        categoryId = null,
        kind = runCatching { ContentKind.valueOf(entry.kind) }.getOrDefault(ContentKind.VOD),
        xtreamStreamId = entry.xtreamStreamId ?: entry.seriesId
    )

    private fun keysOf(item: MediaItem, resolvedId: (MediaItem) -> String?): Set<String> {
        val keys = HashSet<String>(2)
        val id = resolvedId(item)
        if (!id.isNullOrBlank()) keys += "tmdb:${item.kind}:$id"
        val title = TmdbTitle.signature(item)
        if (title.isNotBlank()) keys += "title:${item.kind}:$title"
        return keys
    }

    private fun watchIndex(resume: List<ResumeStore.ResumeEntry>): Map<String, Int> {
        val watched = HashMap<String, Int>()
        resume.forEachIndexed { index, entry ->
            if (entry.catalogId.isNotBlank()) watched.putIfAbsent(entry.catalogId, index)
            if (entry.key.isNotBlank()) watched.putIfAbsent(entry.key, index)
            entry.xtreamStreamId?.let { watched.putIfAbsent("stream:${entry.kind}:$it", index) }
            entry.seriesId?.let { watched.putIfAbsent("series:$it", index) }
        }
        return watched
    }

    private fun watchRank(item: MediaItem, watched: Map<String, Int>): Int {
        watched[item.id]?.let { return it }
        item.xtreamStreamId?.let { sid ->
            watched["stream:${item.kind.name}:$sid"]?.let { return it }
            if (item.kind == ContentKind.SERIES) watched["series:$sid"]?.let { return it }
        }
        return Int.MAX_VALUE
    }

    private fun prefer(
        a: MediaItem,
        b: MediaItem,
        watched: Map<String, Int>,
        catalogIndex: Map<String, Int>
    ): MediaItem {
        val aw = watchRank(a, watched)
        val bw = watchRank(b, watched)
        if (aw != bw) return if (aw < bw) a else b
        val aq = qualityScore(a.name)
        val bq = qualityScore(b.name)
        if (aq != bq) return if (aq > bq) a else b
        val ai = catalogIndex[a.id] ?: Int.MAX_VALUE
        val bi = catalogIndex[b.id] ?: Int.MAX_VALUE
        if (ai != bi) return if (ai < bi) a else b
        return a
    }
}
