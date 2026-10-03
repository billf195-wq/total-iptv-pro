package com.totaliptv.pro.desktop.update

import java.net.URI
import java.nio.file.Path

/**
 * In-app updates come from GitHub Releases for billf195-wq/total-iptv-pro.
 * Saved LAN shelf URLs from older builds are ignored.
 */
object UpdateSources {
    const val GITHUB_REPO = "billf195-wq/total-iptv-pro"
    const val GITHUB_LATEST_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    const val GITHUB_RELEASES_API = "https://api.github.com/repos/$GITHUB_REPO/releases"
    const val GITHUB_RELEASES_PAGE = "https://github.com/$GITHUB_REPO/releases/latest"
    const val GITHUB_RELEASES_PER_PAGE = 30
    const val GITHUB_RELEASE_LIST_PAGES = 3

    fun releasesListUrl(page: Int): String =
        "$GITHUB_RELEASES_API?per_page=$GITHUB_RELEASES_PER_PAGE&page=$page"

    fun isPrivateLan(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull() ?: return false
        if (host == "localhost" || host == "0.0.0.0" || host.endsWith(".local")) return true
        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("127.")) return true
        if (host.startsWith("172.")) {
            val second = host.removePrefix("172.").substringBefore('.').toIntOrNull() ?: return false
            if (second in 16..31) return true
        }
        return false
    }

    fun shelfForCheck(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || isPrivateLan(trimmed)) return GITHUB_RELEASES_PAGE
        return trimmed
    }

    fun usesGithubReleases(shelf: String): Boolean {
        val host = runCatching { URI(shelf.trim()).host?.lowercase() }.getOrNull() ?: return false
        return host == "github.com" || host == "api.github.com"
    }

    fun windowsInstallRoot(localAppData: String): Path =
        Path.of(localAppData, "TotalIptvPro")

    /** Newer dotted version (1.2.21) than [localName]. Pre-release suffixes are ignored. */
    fun isNewerName(remoteName: String, localName: String): Boolean {
        val remote = versionParts(remoteName)
        val local = versionParts(localName)
        val n = maxOf(remote.size, local.size)
        for (i in 0 until n) {
            val r = remote.getOrElse(i) { 0 }
            val l = local.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    private fun versionParts(name: String): List<Int> =
        name.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
}
