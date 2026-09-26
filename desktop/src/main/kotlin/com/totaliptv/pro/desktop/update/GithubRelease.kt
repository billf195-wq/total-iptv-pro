package com.totaliptv.pro.desktop.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object GithubRelease {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Newest published release in [pages] (GitHub order, newest first) that has
     * this OS's desktop asset. Drafts and prereleases are skipped. Android-only
     * releases are skipped so they do not hide an older desktop package.
     */
    fun select(pages: List<String>, windows: Boolean, localName: String, localCode: Int): RemoteVersion? {
        for (page in pages) {
            val releases = runCatching { json.parseToJsonElement(page) as? JsonArray }.getOrNull() ?: continue
            for (el in releases) {
                val root = el as? JsonObject ?: continue
                if (flag(root, "draft") || flag(root, "prerelease")) continue
                fromRelease(root, localName, localCode, windows)?.let { return it }
            }
        }
        return null
    }

    /** True when [body] is a short or empty page, so later pages will not exist. */
    fun pageEnded(body: String): Boolean {
        val releases = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return true
        return releases.size < UpdateSources.GITHUB_RELEASES_PER_PAGE
    }

    fun parse(body: String, localName: String, localCode: Int): RemoteVersion? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        return fromRelease(root, localName, localCode, windows = null)
    }

    private fun fromRelease(
        root: JsonObject,
        localName: String,
        localCode: Int,
        windows: Boolean?
    ): RemoteVersion? {
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.removePrefix("v")
            ?.removePrefix("V")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val assets = root["assets"] as? JsonArray
        var linux: String? = null
        var windowsUrl: String? = null
        assets?.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            when {
                name.contains("windows", ignoreCase = true) && name.endsWith(".zip", ignoreCase = true) ->
                    windowsUrl = url
                name.endsWith(".tar.gz", ignoreCase = true) || name.endsWith(".tgz", ignoreCase = true) ->
                    linux = url
            }
        }
        val matchesOs = when (windows) {
            true -> !windowsUrl.isNullOrBlank()
            false -> !linux.isNullOrBlank()
            null -> !linux.isNullOrBlank() || !windowsUrl.isNullOrBlank()
        }
        if (!matchesOs) return null
        val notedCode = Regex("""versionCode\s*[:=]\s*(\d+)""")
            .find(notes)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val code = notedCode ?: if (UpdateSources.isNewerName(tag, localName)) localCode + 1 else localCode
        return RemoteVersion(
            versionCode = code,
            versionName = tag,
            file = linux ?: windowsUrl.orEmpty(),
            fileWindows = windowsUrl,
            fileLinux = linux
        )
    }

    private fun flag(root: JsonObject, key: String): Boolean =
        root[key]?.jsonPrimitive?.booleanOrNull == true
}
