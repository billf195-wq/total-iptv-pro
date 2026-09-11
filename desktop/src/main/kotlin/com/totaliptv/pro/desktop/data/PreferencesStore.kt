package com.totaliptv.pro.desktop.data

import com.totaliptv.pro.desktop.util.AppPaths
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText

object PreferencesStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val dir: Path = AppPaths.configDir
    private val file: Path = dir.resolve("prefs.json")
    private val tmp: Path = dir.resolve("prefs.json.tmp")

    fun load(): SavedPrefs {
        return try {
            if (!file.exists()) return SavedPrefs()
            json.decodeFromString<SavedPrefs>(file.readText())
        } catch (_: Exception) {
            SavedPrefs()
        }
    }

    fun save(prefs: SavedPrefs) {
        Files.createDirectories(dir)
        // Atomic-ish replace so a crash mid-write does not wipe login
        tmp.writeText(json.encodeToString(prefs))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun clear() {
        if (file.exists()) Files.delete(file)
    }

    private fun Path.writeText(text: String) {
        Files.writeString(this, text)
    }
}
