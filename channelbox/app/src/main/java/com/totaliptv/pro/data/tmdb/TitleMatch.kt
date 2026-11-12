package com.totaliptv.pro.data.tmdb

import java.text.Normalizer

/**
 * Title normalization for matching provider titles ("EN - The Boys (2019) [4K]") to TMDB
 * titles ("The Boys", 2019). Pure Kotlin, unit-tested.
 */
object TitleMatch {
    private val yearParen = Regex("""[(\[]\s*((?:19|20)\d{2})\s*[)\]]""")
    private val yearTail = Regex("""(?:^|[\s\-–|:])((?:19|20)\d{2})\s*$""")
    private val bracketed = Regex("""[\[(][^\])]*[\])]""")
    private val marks = Regex("""\p{M}+""")
    private val nonAlnum = Regex("""[^a-z0-9]+""")
    private val spaces = Regex("""\s+""")

    /** Leading language / quality tags: "EN - ", "|EN| ", "4K-", "[MULTI] ", "NF: ". */
    private const val TAG =
        """(?:[a-z]{2}|[a-z]{2}-[a-z]{2}|4k|uhd|fhd|hd|sd|hdr|vod|multi|sub|dub|3d|top|new|nf|nflx|amzn|atv\+?|hbo|max|dsnp)"""

    /** "[EN] ", "|EN| - ", "EN: ", "EN - " (a bare hyphen needs a space after it, so "Ex-Machina" stays). */
    private val leadingTag = Regex(
        """^\s*(?:[\[|(]\s*$TAG\s*[\]|)]\s*[|:\-–]?\s*|$TAG(?:\s*[|:]\s*|\s*[\-–]\s+))""",
        RegexOption.IGNORE_CASE
    )
    private val trailingTags = setOf(
        "4k", "uhd", "fhd", "hd", "sd", "hdr", "hdr10", "dv", "1080p", "720p", "2160p",
        "multi", "sub", "subs", "dub", "dubbed", "vostfr", "en", "eng", "english"
    )

    /** Year from "(2019)", "[2019]" or a trailing " - 2019"; null when absent. */
    fun extractYear(raw: String?): Int? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        yearParen.findAll(s).lastOrNull()?.let { return it.groupValues[1].toIntOrNull() }
        yearTail.find(s)?.let { m ->
            val y = m.groupValues[1].toIntOrNull()
            // Keep titles that ARE a year ("1917", "2012") intact.
            val rest = s.substring(0, m.range.first).trim()
            if (y != null && rest.isNotEmpty()) return y
        }
        return null
    }

    /** Year from a date string like "2019-07-25" / "2019". */
    fun yearFromDate(raw: String?): Int? {
        val s = raw?.trim().orEmpty()
        if (s.length < 4) return null
        return s.substring(0, 4).toIntOrNull()?.takeIf { it in 1870..2100 }
    }

    /** Lowercase ASCII key: tags, years, brackets and punctuation removed; "&" -> "and". */
    fun normalize(raw: String?): String {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        // Strip up to three leading tags ("EN - 4K - Title").
        repeat(3) {
            val m = leadingTag.find(s) ?: return@repeat
            if (m.range.first == 0 && m.range.last + 1 < s.length) s = s.substring(m.range.last + 1)
        }
        s = bracketed.replace(s, " ")
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "")
        s = s.lowercase().replace("&", " and ").replace("'", "").replace("’", "")
        s = nonAlnum.replace(s, " ").trim()
        var tokens = s.split(spaces).filter { it.isNotEmpty() }
        // Drop a trailing year left after brackets were removed (" - 2019"), and quality tags.
        while (tokens.size > 1 && (tokens.last() in trailingTags || isYear(tokens.last()))) {
            tokens = tokens.dropLast(1)
        }
        if (tokens.size > 1 && tokens.first() == "the") tokens = tokens.drop(1)
        return tokens.joinToString(" ")
    }

    /** Keys a TMDB title is indexed under: full title, original title, and the part before ":". */
    fun keysForTmdb(title: String?, originalTitle: String?): Set<String> =
        exactKeysForTmdb(title, originalTitle) + shortKeysForTmdb(title, originalTitle)

    /** Full normalized title and original title. */
    fun exactKeysForTmdb(title: String?, originalTitle: String?): Set<String> =
        listOf(title, originalTitle).map { normalize(it) }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())

    /**
     * The part before ":" ("Mission: Impossible - …" -> "mission"), only when still specific
     * (two words or 6+ chars). Weaker than an exact key: the index requires the same year.
     */
    fun shortKeysForTmdb(title: String?, originalTitle: String?): Set<String> {
        val exact = exactKeysForTmdb(title, originalTitle)
        val out = LinkedHashSet<String>()
        for (t in listOf(title, originalTitle)) {
            val head = t?.substringBefore(':', missingDelimiterValue = "")?.trim().orEmpty()
            if (head.isEmpty()) continue
            val hn = normalize(head)
            if (hn.isNotEmpty() && hn !in exact && (hn.contains(' ') || hn.length >= 6)) out += hn
        }
        return out
    }

    private fun isYear(t: String): Boolean =
        t.length == 4 && (t.startsWith("19") || t.startsWith("20")) && t.all { it.isDigit() }
}
