package com.totaliptv.pro.data.lock

/**
 * Conservative adult-category detection by category NAME only (titles are never scanned, so a
 * movie called "xXx" stays visible). Case-insensitive. Matches whole markers only:
 * "adult"/"adults", "xxx", "18+"/"+18", "for adults", "porn". "Young adult" and "Adult Swim"
 * are deliberately not matched.
 */
object AdultCategories {
    private val exclusions = listOf(
        Regex("""\byoung[\s\-_]*adults?\b"""),
        Regex("""\badult[\s\-_]*swim\b""")
    )
    private val markers = listOf(
        Regex("""(?<![a-z0-9])adults?(?![a-z0-9])"""),
        Regex("""(?<![a-z0-9])xxx(?![a-z0-9])"""),
        Regex("""(?<![0-9])18\s?\+"""),
        Regex("""\+\s?18(?![0-9])"""),
        Regex("""(?<![a-z0-9])porn"""),
        Regex("""\bfor\s+adults\b""")
    )

    fun isAdult(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        var text = name.lowercase()
        for (ex in exclusions) text = ex.replace(text, " ")
        return markers.any { it.containsMatchIn(text) }
    }
}
