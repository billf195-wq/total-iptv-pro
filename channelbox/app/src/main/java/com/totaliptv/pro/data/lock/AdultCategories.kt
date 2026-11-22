package com.totaliptv.pro.data.lock

/**
 * Conservative adult-category detection by category NAME ([isAdult]). Search also uses
 * [isAdultTitle], which skips a bare "xxx" so a movie called "xXx" stays visible. Case-insensitive. Matches whole markers only:
 * "adult"/"adults" (and adultos, adulte, adulti), "xxx" (also spaced or dotted), "18+"/"+18" (also spaced
 * or punctuated), "for adults", "porn", "erotic". "Young adult" and "Adult Swim"
 * are deliberately not matched.
 */
object AdultCategories {
    private val exclusions = listOf(
        Regex("""\byoung[\s\-_]*adults?\b"""),
        Regex("""\badult[\s\-_]*swim\b"""),
        Regex("""\bj[oó]venes[\s\-_]*adult(?:os|as)\b""")
    )
    private val markers = listOf(
        // adult / adults, plus 1.4.108: adultos, adultas, adulte(s), adulti (not "adultery")
        Regex("""(?<![a-z0-9])adult(?:s|e|es|o|os|a|as|i)?(?![a-z0-9])"""),
        // xxx, and 1.4.108: spaced or punctuated "x x x", "x.x.x", "x-x-x"
        Regex("""(?<![a-z0-9])x[\s.\-_*]?x[\s.\-_*]?x(?![a-z0-9])"""),
        // 18+ / +18, and 1.4.108: "18 +", "(18+)", "[18 +]", "18-+", "+ 18", "18 plus", fullwidth plus
        Regex("""(?<![0-9])18\s*[-.]?\s*[+\uFF0B]"""),
        Regex("""[+\uFF0B]\s*[-.]?\s*18(?![0-9])"""),
        Regex("""(?<![0-9])18\s*-?\s*plus\b"""),
        Regex("""(?<![a-z0-9])porn"""),
        Regex("""\bfor\s+adults\b"""),
        // 1.4.108: erotic, erotica, erotico(s), erotique, erotik, erotisch
        Regex("""(?<![a-z0-9])er[oó]ti(?:c|ca|cas|co|cos|que|k|sch)(?![a-z0-9])""")
    )

    fun isAdult(name: String?): Boolean = matches(name, includeXxx = true)

    /**
     * Title check for Search. Same markers as [isAdult], except a bare "xxx" so the
     * mainstream movie "xXx" is not hidden. "Young adult" and "Adult Swim" stay excluded.
     */
    fun isAdultTitle(name: String?): Boolean = matches(name, includeXxx = false)

    private fun matches(name: String?, includeXxx: Boolean): Boolean {
        if (name.isNullOrBlank()) return false
        var text = name.lowercase()
        for (ex in exclusions) text = ex.replace(text, " ")
        val xxx = markers[1]
        return markers.any { marker ->
            (includeXxx || marker !== xxx) && marker.containsMatchIn(text)
        }
    }
}
