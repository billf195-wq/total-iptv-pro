package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Last good Xtream movie + series lists on disk, so Movies shows instantly on
 * restart and a failed refresh never leaves the screen empty (1.4.91).
 */
object VodDiskCache {
    const val FILE_NAME = "vod-catalog-cache.json"

    @Serializable
    data class Snapshot(
        val sourceId: String,
        val savedAtMs: Long,
        val categories: List<Category>,
        val items: List<MediaItem>
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun write(dir: File, sourceId: String, categories: List<Category>, items: List<MediaItem>, nowMs: Long = System.currentTimeMillis()) {
        val cats = categories.filter { it.kind != ContentKind.LIVE }
        val its = items.filter { it.kind != ContentKind.LIVE }
        if (its.none { it.kind == ContentKind.VOD }) return
        dir.mkdirs()
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(Snapshot.serializer(), Snapshot(sourceId, nowMs, cats, its)))
        val dest = File(dir, FILE_NAME)
        if (!tmp.renameTo(dest)) {
            dest.delete()
            tmp.renameTo(dest)
        }
    }

    /** Returns null if missing, unreadable, for another source, or holds no movies. */
    fun read(dir: File, sourceId: String): Snapshot? {
        val f = File(dir, FILE_NAME)
        if (!f.isFile) return null
        val snap = runCatching { json.decodeFromString(Snapshot.serializer(), f.readText()) }.getOrNull() ?: return null
        if (snap.sourceId != sourceId) return null
        if (snap.items.none { it.kind == ContentKind.VOD }) return null
        return snap
    }

    fun clear(dir: File) {
        File(dir, FILE_NAME).delete()
    }

    /** Merge rule: only accept a fresh list that actually has items. */
    fun shouldReplace(newItems: List<MediaItem>): Boolean = newItems.isNotEmpty()
}
