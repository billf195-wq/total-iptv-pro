package com.totaliptv.pro.desktop.artwork

import com.totaliptv.pro.desktop.data.ContentKind
import com.totaliptv.pro.desktop.data.MediaItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Turns an IPTV row into a TMDB search, then keeps a hit only when the title and year match.
 * A more popular near-miss is ignored.
 */
object TmdbTitle {
    private val json = Json { ignoreUnknownKeys = true }
    private val parenYear = Regex("""[\[(]\s*((?:19|20)\d{2})\s*[\])]""")
    private val trailingYear = Regex("""(?:^|[\s.\-_])((?:19|20)\d{2})\s*$""")
    private val quality = Regex(
        """(?i)(?<![a-z0-9])(?:2160p|1080p|720p|576p|480p|4320p|4k|uhd|fhd|hdrip|webrip|web-?dl|bluray|blu-ray|bdrip|brrip|dvdrip|hdtv|x264|x265|h\.?264|h\.?265|hevc|aac|ac3|dts|hdr10|hdr|10bit|8bit|amzn|dsnp|hd|sd)(?![a-z0-9])"""
    )
    private val bracketPrefix = Regex(
        """(?i)^[\[|(]+\s*(?:english|french|german|spanish|italian|vostfr|truefrench|multi|vff|eng|en|fr|de|es|it|pt|nl|ar|tr|ru|pl|sv|da|fi|vf|vo)\s*[\]|)]+\s*"""
    )
    private val taggedPrefix = Regex(
        """(?i)^(?:english|french|german|spanish|italian|vostfr|truefrench|multi|vff|eng|en|fr|de|es|it|pt|nl|ar|tr|ru|pl|sv|da|fi|vf|vo)\s*[-:|–—]\s*"""
    )
    private val spacedCode = Regex(
        """(?i)^(en|eng|fr|de|es|pt|nl|ar|tr|ru|pl|multi|vf|vostfr)\s+(?=\S)"""
    )

    data class Query(val title: String, val year: Int, val kind: ContentKind)

    data class Hit(
        val tmdbId: String,
        val average: Double,
        val votes: Int,
        val popularity: Double
    )

    /** Null when the row has no usable title or no year, so a search would not be confident. */
    fun query(item: MediaItem): Query? {
        if (item.kind != ContentKind.VOD && item.kind != ContentKind.SERIES) return null
        val name = item.name.replace('\u00A0', ' ').trim()
        if (name.isEmpty()) return null
        val paren = parenYear.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val explicit = item.year?.takeIf { it in 1888..2100 }
        val trail = trailingYear.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val year = paren ?: explicit ?: trail ?: return null
        var title = parenYear.replace(name, " ")
        title = quality.replace(title, " ")
        title = stripPrefixes(title)
        title = tidy(title)
        if (paren == null && trail != null && trail == year) {
            val withoutTrail = tidy(title.replace(Regex("""\s+$trail\s*$"""), ""))
            if (withoutTrail.any { it.isLetter() }) title = withoutTrail
        }
        if (title.length < 2 || !title.any { it.isLetter() }) return null
        return Query(title, year, item.kind)
    }

    fun lookupKey(query: Query): String {
        val type = if (query.kind == ContentKind.SERIES) "tv" else "movie"
        return "lookup:$type:${normalize(query.title)}|${query.year}"
    }

    fun searchPath(query: Query): String {
        val encoded = URLEncoder.encode(query.title, Charsets.UTF_8)
        return if (query.kind == ContentKind.SERIES) {
            "search/tv?query=$encoded&first_air_date_year=${query.year}"
        } else {
            "search/movie?query=$encoded&year=${query.year}"
        }
    }

    fun normalize(raw: String): String {
        return raw.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex(" +"), " ")
    }

    /**
     * Highest-popularity result whose normalized title (or original title) and release year
     * both match. Anything less is not returned.
     */
    fun bestMatch(body: String, query: Query): Hit? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val results = root["results"]?.jsonArray ?: return null
        val want = normalize(query.title)
        if (want.isBlank()) return null
        var best: Hit? = null
        for (element in results) {
            val node = runCatching { element.jsonObject }.getOrNull() ?: continue
            val titles = listOf("title", "name", "original_title", "original_name").mapNotNull { key ->
                node[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            }
            if (titles.none { normalize(it) == want }) continue
            val resultYear = yearOf(node) ?: continue
            if (resultYear != query.year) continue
            val id = node["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
                ?: continue
            val popularity = node["popularity"]?.jsonPrimitive?.doubleOrNull
                ?: node["popularity"]?.jsonPrimitive?.intOrNull?.toDouble()
                ?: 0.0
            val average = node["vote_average"]?.jsonPrimitive?.doubleOrNull
                ?: node["vote_average"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: 0.0
            val votes = node["vote_count"]?.jsonPrimitive?.intOrNull
                ?: node["vote_count"]?.jsonPrimitive?.doubleOrNull?.toInt()
                ?: 0
            val hit = Hit(id, average, votes, popularity)
            if (best == null || hit.popularity > best.popularity) best = hit
        }
        return best
    }

    fun isSearchBody(body: String): Boolean {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return false
        return root["results"] != null
    }

    private fun yearOf(node: kotlinx.serialization.json.JsonObject): Int? {
        val text = node["release_date"]?.jsonPrimitive?.contentOrNull
            ?: node["first_air_date"]?.jsonPrimitive?.contentOrNull
            ?: return null
        return Regex("""(?:19|20)\d{2}""").find(text)?.value?.toIntOrNull()
    }

    private fun stripPrefixes(raw: String): String {
        var title = raw.trim()
        repeat(4) {
            val next = when {
                bracketPrefix.containsMatchIn(title) -> bracketPrefix.replaceFirst(title, "")
                taggedPrefix.containsMatchIn(title) -> taggedPrefix.replaceFirst(title, "")
                spacedCode.containsMatchIn(title) -> spacedCode.replaceFirst(title, "")
                else -> title
            }.trim()
            if (next == title) return title
            title = next
        }
        return title
    }

    private fun tidy(raw: String): String {
        return raw.replace(Regex("""[\s.\-_\[\]()|]+"""), " ")
            .trim()
            .trim(' ', '-', '|', ':', '.', '/')
            .replace(Regex(" +"), " ")
            .trim()
    }
}
