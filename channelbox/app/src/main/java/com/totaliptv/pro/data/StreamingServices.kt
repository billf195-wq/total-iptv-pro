package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem

/** Streaming services surfaced as virtual groups in Movies / Series. */
enum class StreamingService(val key: String, val label: String) {
    NETFLIX("netflix", "Netflix"),
    PRIME("prime", "Prime Video"),
    HBO("hbo", "HBO / Max"),
    APPLE("apple", "Apple TV+")
}

/**
 * Keyword matcher for streaming-service groups. Pure Kotlin (no Android) so it is unit-tested.
 *
 * A title belongs to a service when its provider category name OR its title matches that
 * service. Matching is case-insensitive and word-boundary aware ("netflixing" / "amazing"
 * never match). Title matching is stricter than category matching: bare "amazon" or "prime"
 * only count inside a category name, so "Amazon Women on the Moon", "Prime Suspect" and
 * "Optimus Prime" are not tagged Prime Video.
 */
object StreamingServices {
    private const val L = "(?<![a-z0-9])"
    private const val R = "(?![a-z0-9])"

    private fun rx(body: String) = Regex("$L(?:$body)$R")

    private val netflix = rx("""netflix|nflx""")
    private val primeStrong = rx("""amazon\s*prime(?:\s*video)?|prime\s*video|amzn|amazon\s+originals?|amazon\s+studios""")
    private val amazonWord = rx("""amazon""")
    private val primeWord = rx("""prime""")
    private val primeContext = rx("""vod|movies?|films?|series|shows?|tv|originals?|cinema|4k|uhd""")
    /** "Prime Time", "Optimus Prime", "Prime Suspect" etc. — never a streaming group. */
    private val primeFalse = rx("""prime\s*time|optimus\s*prime|prime\s*suspect|prime\s*minister|prime\s*number|prime\s*rib""")
    private val hbo = rx("""hbo(?:\s*max)?|hbomax|max\s+originals?""")
    // "tv+" / "apple+" / "atv+" end in '+', so the right boundary is implied by the '+'.
    private val apple = Regex("""$L(?:apple\s*tv\s*\+|apple\s*\+|appletv\s*\+|atv\s*\+|apple\s*tv$R|appletv$R)""")

    private val TITLE_HINTS = listOf("netflix", "nflx", "amazon", "prime", "amzn", "hbo", "max", "apple", "atv")

    private val segmentSplit = Regex("""[|:\-–—/\[\]()•*#]+""")

    /** Services matched by a provider category / group name. */
    fun matchCategoryName(name: String?): Set<StreamingService> {
        val n = name?.lowercase()?.trim().orEmpty()
        if (n.isEmpty()) return emptySet()
        val out = LinkedHashSet<StreamingService>()
        if (netflix.containsMatchIn(n)) out += StreamingService.NETFLIX
        if (matchesPrimeCategory(n)) out += StreamingService.PRIME
        if (hbo.containsMatchIn(n)) out += StreamingService.HBO
        if (apple.containsMatchIn(n)) out += StreamingService.APPLE
        return out
    }

    /** Services matched by a title alone (stricter: no bare "amazon" / "prime"). */
    fun matchTitle(title: String?): Set<StreamingService> {
        val n = title?.lowercase()?.trim().orEmpty()
        if (n.isEmpty()) return emptySet()
        // Cheap prefilter: most titles mention no service, so skip the regexes entirely.
        if (TITLE_HINTS.none { n.contains(it) }) return emptySet()
        val out = LinkedHashSet<StreamingService>()
        if (netflix.containsMatchIn(n)) out += StreamingService.NETFLIX
        if (primeStrong.containsMatchIn(n)) out += StreamingService.PRIME
        if (hbo.containsMatchIn(n)) out += StreamingService.HBO
        if (apple.containsMatchIn(n)) out += StreamingService.APPLE
        return out
    }

    fun servicesFor(categoryName: String?, title: String?): Set<StreamingService> {
        val fromCat = matchCategoryName(categoryName)
        val fromTitle = matchTitle(title)
        if (fromTitle.isEmpty()) return fromCat
        if (fromCat.isEmpty()) return fromTitle
        return fromCat + fromTitle
    }

    private fun matchesPrimeCategory(n: String): Boolean {
        if (primeStrong.containsMatchIn(n)) return true
        if (amazonWord.containsMatchIn(n)) return true
        if (!primeWord.containsMatchIn(n)) return false
        if (primeFalse.containsMatchIn(n)) return false
        // Standalone group token: "PRIME | Movies", "EN - PRIME", "[PRIME]".
        if (n.split(segmentSplit).any { it.trim() == "prime" }) return true
        // "Prime Movies", "Prime Series", "Prime VOD".
        return primeContext.containsMatchIn(n)
    }

    // ---- Virtual category ids -------------------------------------------------------------

    private const val VOD_PREFIX = "vod-svc-"
    private const val SERIES_PREFIX = "series-svc-"

    fun categoryId(service: StreamingService, kind: ContentKind): String = when (kind) {
        ContentKind.SERIES -> SERIES_PREFIX + service.key
        else -> VOD_PREFIX + service.key
    }

    fun isServiceCategoryId(id: String?): Boolean =
        id != null && (id.startsWith(VOD_PREFIX) || id.startsWith(SERIES_PREFIX))

    /** Parse a virtual id back to (service, kind); null for ordinary provider ids. */
    fun parseCategoryId(id: String?): Pair<StreamingService, ContentKind>? {
        if (id == null) return null
        val (kind, key) = when {
            id.startsWith(VOD_PREFIX) -> ContentKind.VOD to id.removePrefix(VOD_PREFIX)
            id.startsWith(SERIES_PREFIX) -> ContentKind.SERIES to id.removePrefix(SERIES_PREFIX)
            else -> return null
        }
        val service = StreamingService.entries.firstOrNull { it.key == key } ?: return null
        return service to kind
    }

    /**
     * Group movie / series items by service. [categoryName] resolves a provider category
     * name for an item (falls back to [MediaItem.groupTitle]). Items keep catalog order and
     * appear at most once per service.
     */
    fun group(
        items: List<MediaItem>,
        categoryName: (MediaItem) -> String? = { it.groupTitle }
    ): Map<Pair<StreamingService, ContentKind>, List<MediaItem>> {
        val out = LinkedHashMap<Pair<StreamingService, ContentKind>, MutableList<MediaItem>>()
        val seen = HashSet<String>()
        // Category names repeat for thousands of items; match each distinct name once.
        val catCache = HashMap<String, Set<StreamingService>>()
        for (item in items) {
            if (item.kind != ContentKind.VOD && item.kind != ContentKind.SERIES) continue
            val cat = categoryName(item) ?: item.groupTitle
            val fromCat = if (cat == null) emptySet() else catCache.getOrPut(cat) { matchCategoryName(cat) }
            val services = fromCat + matchTitle(item.name)
            for (svc in services) {
                val k = svc to item.kind
                if (seen.add("${svc.key}|${item.kind}|${item.id}")) {
                    out.getOrPut(k) { mutableListOf() } += item
                }
            }
        }
        return out
    }

    /** Non-empty virtual categories for [kind] in service order. */
    fun categoriesFor(
        kind: ContentKind,
        grouped: Map<Pair<StreamingService, ContentKind>, List<MediaItem>>
    ): List<Category> = StreamingService.entries.mapNotNull { svc ->
        if (grouped[svc to kind].isNullOrEmpty()) null
        else Category(id = categoryId(svc, kind), name = svc.label, kind = kind)
    }
}
