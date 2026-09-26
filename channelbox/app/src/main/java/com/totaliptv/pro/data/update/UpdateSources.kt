package com.totaliptv.pro.data.update

/**
 * Pure update-source rules. GitHub Releases is checked first. The LAN shelf is
 * the fallback. Desktop v1.2.x can be marked Latest, so callers must list
 * releases and match the TV or phone APK asset — not GET /releases/latest.
 */
object UpdateSources {
    const val GITHUB_RELEASES_URL =
        "https://api.github.com/repos/billf195-wq/total-iptv-pro/releases"

    const val UPDATE_UNAVAILABLE =
        "Couldn't check for updates. Check your connection and try again."

    private const val TV_PREFIX = "TotalIPTVPro-android-tv-"
    private const val PHONE_PREFIX = "TotalIPTVPro-android-phone-"
    /** Uploaded debug builds, and release builds signed with the permanent key. */
    private val APK_SUFFIXES = listOf("-debug.apk", "-release.apk")

    data class Asset(val name: String, val url: String)

    data class ReleaseListing(
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList()
    )

    data class PickedApk(val versionName: String, val url: String)

    /**
     * A shelf is optional. Blank means GitHub only, so a fresh install never
     * contacts a private update server.
     */
    fun shouldCheckShelf(baseUrl: String?): Boolean = !baseUrl.isNullOrBlank()

    /** Kept so older call sites still compile. Shelf addresses are not rewritten. */
    fun migrateShelfHost(raw: String): String = raw

    /**
     * Highest matching APK that is newer than [currentVersionName].
     * [releasesNewestFirst] should be GitHub's order (newest published first)
     * so equal versions keep the newer release's asset.
     */
    fun pickNewerApk(
        releasesNewestFirst: List<ReleaseListing>,
        flavor: String,
        currentVersionName: String
    ): PickedApk? {
        var best: PickedApk? = null
        for (release in releasesNewestFirst) {
            if (release.draft || release.prerelease) continue
            for (asset in release.assets) {
                val version = versionFromAsset(asset.name, flavor) ?: continue
                if (asset.url.isBlank()) continue
                val currentBest = best
                if (currentBest == null || compareVersions(version, currentBest.versionName) > 0) {
                    best = PickedApk(version, asset.url)
                }
            }
        }
        val picked = best ?: return null
        return if (compareVersions(picked.versionName, currentVersionName) > 0) picked else null
    }

    fun versionFromAsset(name: String, flavor: String): String? {
        val prefix = when (flavor.trim().lowercase()) {
            "phone" -> PHONE_PREFIX
            "tv" -> TV_PREFIX
            else -> return null
        }
        val trimmed = name.trim()
        val lower = trimmed.lowercase()
        val prefixLower = prefix.lowercase()
        if (!lower.startsWith(prefixLower)) return null
        val suffix = APK_SUFFIXES.firstOrNull { lower.endsWith(it) } ?: return null
        val version = trimmed.substring(prefix.length, trimmed.length - suffix.length)
        return version.takeIf { it.isNotBlank() }
    }

    /**
     * Numeric dotted compare. A trailing `-phone` (versionName) does not change
     * the number, so `1.4.36-phone` equals asset `1.4.36`.
     */
    fun compareVersions(left: String, right: String): Int {
        val a = versionParts(left)
        val b = versionParts(right)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    fun versionParts(raw: String): List<Int> {
        val core = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-phone")
            .substringBefore("-debug")
            .substringBefore("-tv")
        if (core.isBlank()) return emptyList()
        return core.split('.', '-', '_')
            .mapNotNull { token -> token.toIntOrNull() }
    }

    /** GitHub `Link` header page URL for `rel="next"`, if present. */
    fun nextPageUrl(linkHeader: String?): String? {
        if (linkHeader.isNullOrBlank()) return null
        for (part in linkHeader.split(',')) {
            val bits = part.split(';').map { it.trim() }
            val isNext = bits.any { rel ->
                rel.equals("rel=\"next\"", ignoreCase = true) ||
                    rel.equals("rel='next'", ignoreCase = true)
            }
            if (!isNext) continue
            val url = bits.firstOrNull()?.removePrefix("<")?.removeSuffix(">")?.trim()
            if (!url.isNullOrBlank()) return url
        }
        return null
    }
}
