package com.totaliptv.pro.desktop.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object GithubRelease {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String, localName: String, localCode: Int): RemoteVersion? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.removePrefix("v")
            ?.removePrefix("V")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val assets = root["assets"] as? JsonArray
        var linux: String? = null
        var windows: String? = null
        assets?.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            when {
                name.contains("windows", ignoreCase = true) && name.endsWith(".zip", ignoreCase = true) ->
                    windows = url
                name.endsWith(".tar.gz", ignoreCase = true) || name.endsWith(".tgz", ignoreCase = true) ->
                    linux = url
            }
        }
        val notedCode = Regex("""versionCode\s*[:=]\s*(\d+)""")
            .find(notes)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val code = notedCode ?: if (UpdateSources.isNewerName(tag, localName)) localCode + 1 else localCode
        if (linux.isNullOrBlank() && windows.isNullOrBlank()) return null
        return RemoteVersion(
            versionCode = code,
            versionName = tag,
            file = linux ?: windows.orEmpty(),
            fileWindows = windows,
            fileLinux = linux
        )
    }
}
