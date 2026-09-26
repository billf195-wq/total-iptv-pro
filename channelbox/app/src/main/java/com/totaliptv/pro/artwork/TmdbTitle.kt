package com.totaliptv.pro.artwork

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class TmdbSearchHit(
    val id: String,
    val title: String,
    val year: Int?,
    val popularity: Double,
    val average: Double,
    val votes: Int
)

/** Title cleaning and the confident search match (exact normalized title, year when we have one). */
object TmdbTitle {
    data class Cleaned(val title: String, val year: Int?, val normalized: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val yearWrapped = Regex("""[\[(]((?:19|20)\d{2})[\])]""")
    private val standaloneYear = Regex("""(?<![0-9])((?:19|20)\d{2})(?![0-9])""")
    private val quality = Regex(
        """(?i)\b(?:2160p|1080p|720p|576p|480p|4320p|4k|uhd|hdr10\+|hdr10|hdr|web-?dl|webrip|bluray|blu-ray|bdrip|brrip|dvdrip|hdtv|hdrip|hdcam|x264|x265|h\.?264|h\.?265|hevc|aac|dts|ac3|eac3|ddp|atmos|remux|proper|repack|extended|unrated)\b"""
    )
    private val langWrapped = Regex(
        """(?i)[\[|](?:en|eng|fr|fre|es|spa|de|ger|it|ita|pt|por|nl|ar|tr|ru|pl|multi|dual|vostfr|vf|vo|sub|dub)[\]|]"""
    )
    private val langPrefix = Regex(
        """(?i)^(?:(?:en|eng|fr|fre|es|spa|de|ger|it|ita|pt|por|nl|ar|tr|ru|pl|multi|dual|vostfr|vf|vo)\s*[-:|]\s*)+"""
    )

    fun clean(raw: String): Cleaned {
        var text = raw.trim()
        val dotted = text.count { it == '.' } >= 2 && text.count { it == '.' } > text.count { it == ' ' }
        if (dotted) text = text.replace('.', ' ')
        text = text.replace('_', ' ')

        var year: Int? = null
        yearWrapped.find(text)?.let { match ->
            year = match.groupValues[1].toIntOrNull()
            text = text.removeRange(match.range)
        }
        text = quality.replace(text, " ")
        text = langWrapped.replace(text, " ")
        text = text.replace(Regex("""\s+"""), " ").trim()
        text = langPrefix.replace(text, "")
        if (year == null) {
            val last = standaloneYear.findAll(text).lastOrNull()
            if (last != null) {
                year = last.groupValues[1].toIntOrNull()
                text = (text.removeRange(last.range)).trim()
            }
        }
        text = text.replace(Regex("""^[\s\-–—:|./]+|[\s\-–—:|./]+$"""), "")
        text = text.replace(Regex("""\s{2,}"""), " ").trim()
        return Cleaned(text, year, normalize(text))
    }

    fun normalize(title: String): String =
        title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim()

    /**
     * Exact normalized title. When [cleaned] has a year, the hit year must match.
     * Several matches: highest popularity, then vote count.
     */
    fun pick(hits: List<TmdbSearchHit>, cleaned: Cleaned): TmdbSearchHit? {
        if (cleaned.normalized.isEmpty()) return null
        val exact = hits.filter { normalize(it.title) == cleaned.normalized }
        val pool = if (cleaned.year != null) exact.filter { it.year == cleaned.year } else exact
        if (pool.isEmpty()) return null
        return pool.maxWith(compareBy<TmdbSearchHit> { it.popularity }.thenBy { it.votes })
    }

    fun searchPath(series: Boolean, title: String, year: Int?): String {
        val encoded = URLEncoder.encode(title, StandardCharsets.UTF_8)
        val path = if (series) "search/tv" else "search/movie"
        val yearParam = if (series) "first_air_date_year" else "year"
        val yearQuery = year?.takeIf { it in 1900..2100 }?.let { "&$yearParam=$it" }.orEmpty()
        return "$path?query=$encoded$yearQuery"
    }

    fun interpretSearch(body: String): SearchBody {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return SearchBody.Retry
        val status = root["status_code"]?.jsonPrimitive?.intOrNull
        val results = root["results"]?.jsonArray
        if (results == null) {
            return if (status == null || status == 34) SearchBody.Empty else SearchBody.Retry
        }
        val hits = results.mapNotNull { element ->
            val node = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = node["id"]?.jsonPrimitive?.intOrNull?.toString()
                ?: node["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val title = node["title"]?.jsonPrimitive?.contentOrNull
                ?: node["name"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val date = node["release_date"]?.jsonPrimitive?.contentOrNull
                ?: node["first_air_date"]?.jsonPrimitive?.contentOrNull
            val year = date?.take(4)?.toIntOrNull()
            val popularity = node["popularity"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val average = node["vote_average"]?.jsonPrimitive?.doubleOrNull
                ?: node["vote_average"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: 0.0
            val votes = node["vote_count"]?.jsonPrimitive?.intOrNull
                ?: node["vote_count"]?.jsonPrimitive?.doubleOrNull?.toInt()
                ?: 0
            TmdbSearchHit(id, title, year, popularity, average, votes)
        }
        return SearchBody.Hits(hits)
    }

    sealed class SearchBody {
        data class Hits(val hits: List<TmdbSearchHit>) : SearchBody()
        data object Empty : SearchBody()
        data object Retry : SearchBody()
    }
}
