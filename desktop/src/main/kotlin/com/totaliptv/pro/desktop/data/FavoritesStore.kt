package com.totaliptv.pro.desktop.data

import com.totaliptv.pro.desktop.util.AppPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Local favorites store under the OS config dir (Linux ~/.config/…, Windows %APPDATA%\…).
 * Persists VOD / Series (and optional Live) titles the user hearts.
 */
object FavoritesStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val dir: Path = AppPaths.configDir
    private val file: Path = dir.resolve("favorites.json")
    private val tmp: Path = dir.resolve("favorites.json.tmp")

    @Serializable
    data class FavoriteEntry(
        /** Stable key: same as MediaItem.id (vod-…, series-…, live-…). */
        val key: String,
        val catalogId: String,
        val name: String,
        val kind: String,
        val posterUrl: String? = null,
        val streamUrl: String = "",
        val xtreamStreamId: Int? = null,
        val categoryId: String? = null,
        val addedEpochMs: Long = 0L
    )

    @Serializable
    data class FavoritesFile(
        val entries: List<FavoriteEntry> = emptyList()
    )

    fun load(): List<FavoriteEntry> {
        return try {
            if (!file.exists()) return emptyList()
            json.decodeFromString<FavoritesFile>(file.readText()).entries
                .sortedByDescending { it.addedEpochMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAll(entries: List<FavoriteEntry>) {
        Files.createDirectories(dir)
        tmp.writeText(json.encodeToString(FavoritesFile(entries)))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun isFavorite(key: String, entries: List<FavoriteEntry> = load()): Boolean =
        entries.any { it.key == key }

    fun toggle(item: MediaItem): List<FavoriteEntry> {
        val current = load()
        val key = item.id
        return if (current.any { it.key == key }) {
            val next = current.filterNot { it.key == key }
            saveAll(next)
            next
        } else {
            val entry = FavoriteEntry(
                key = key,
                catalogId = item.id,
                name = item.name,
                kind = item.kind.name,
                posterUrl = item.posterUrl ?: item.logoUrl ?: item.backdropUrl,
                streamUrl = item.streamUrl,
                xtreamStreamId = item.xtreamStreamId,
                categoryId = item.categoryId,
                addedEpochMs = System.currentTimeMillis()
            )
            val next = listOf(entry) + current.filterNot { it.key == key }
            saveAll(next)
            next
        }
    }

    fun remove(key: String): List<FavoriteEntry> {
        val next = load().filterNot { it.key == key }
        saveAll(next)
        return next
    }

    private fun Path.writeText(text: String) {
        Files.writeString(this, text)
    }
}
