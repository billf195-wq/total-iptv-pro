package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.xtream.JsonArrayStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStreamWriter
import java.io.PushbackReader
import java.io.Writer

/**
 * Last good Xtream movie + series lists on disk, so Movies shows instantly on
 * restart and a failed refresh never leaves the screen empty (1.4.91).
 *
 * The file is still a single Snapshot JSON document, but it is written and read
 * as a stream: one item at a time, never as one giant String.
 */
object VodDiskCache {
    const val FILE_NAME = "vod-catalog-cache.json"
    const val ITEM_BATCH = 500

    @Serializable
    data class Snapshot(
        val sourceId: String,
        val savedAtMs: Long,
        val categories: List<Category>,
        val items: List<MediaItem>
    )

    data class Meta(val sourceId: String, val savedAtMs: Long)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun write(dir: File, sourceId: String, categories: List<Category>, items: List<MediaItem>, nowMs: Long = System.currentTimeMillis()) {
        val cats = categories.filter { it.kind != ContentKind.LIVE }
        val its = items.filter { it.kind != ContentKind.LIVE }
        if (its.none { it.kind == ContentKind.VOD }) return
        val writer = Writer(dir, sourceId, cats, seedItems = emptyList(), nowMs = nowMs)
        writer.add(its)
        writer.commit()
    }

    /** Incremental writer used while streaming a network catalog. */
    class Writer(
        dir: File,
        private val sourceId: String,
        categories: List<Category>,
        seedItems: List<MediaItem> = emptyList(),
        nowMs: Long = System.currentTimeMillis()
    ) {
        private val tmp = File(dir, "$FILE_NAME.tmp")
        private val dest = File(dir, FILE_NAME)
        private val out: OutputStreamWriter
        private var itemCount = 0
        private var vodCount = 0
        private var open = true

        init {
            dir.mkdirs()
            out = tmp.outputStream().buffered(16 * 1024).writer(Charsets.UTF_8)
            out.write("{\"sourceId\":")
            out.write(json.encodeToString(sourceId))
            out.write(",\"savedAtMs\":")
            out.write(nowMs.toString())
            out.write(",\"categories\":")
            out.write(json.encodeToString(categories.filter { it.kind != ContentKind.LIVE }))
            out.write(",\"items\":[")
            add(seedItems.filter { it.kind != ContentKind.LIVE })
        }

        fun add(items: List<MediaItem>) {
            if (!open) return
            for (item in items) {
                if (item.kind == ContentKind.LIVE) continue
                if (itemCount > 0) out.write(",")
                out.write(json.encodeToString(MediaItem.serializer(), item))
                itemCount++
                if (item.kind == ContentKind.VOD) vodCount++
            }
        }

        /**
         * Copy the other VOD kind from the last good cache into this temp file,
         * one batch at a time. Does not accumulate that list in memory.
         */
        fun seedFromCache(exclude: ContentKind) {
            if (!open || !dest.isFile) return
            readStreaming(dest.parentFile ?: return, sourceId, {}, { batch ->
                val keep = batch.filter { it.kind != exclude && it.kind != ContentKind.LIVE }
                if (keep.isNotEmpty()) add(keep)
            })
        }

        /** Atomically replace the last good cache. Returns false if there were no movies. */
        fun commit(): Boolean {
            if (!open) return false
            open = false
            return try {
                out.write("]}")
                out.flush()
                out.close()
                if (vodCount == 0) {
                    tmp.delete()
                    false
                } else {
                    if (!tmp.renameTo(dest)) {
                        dest.delete()
                        tmp.renameTo(dest)
                    }
                    true
                }
            } catch (t: Throwable) {
                runCatching { out.close() }
                tmp.delete()
                false
            }
        }

        fun abort() {
            if (!open) return
            open = false
            runCatching { out.close() }
            tmp.delete()
        }
    }

    /** Returns null if missing, unreadable, for another source, or holds no movies. */
    fun read(dir: File, sourceId: String): Snapshot? {
        val cats = ArrayList<Category>()
        val items = ArrayList<MediaItem>()
        val meta = readStreaming(dir, sourceId, { cats.addAll(it) }, { items.addAll(it) }) ?: return null
        if (items.none { it.kind == ContentKind.VOD }) return null
        return Snapshot(sourceId, meta.savedAtMs, cats, items)
    }

    /**
     * Stream a cached snapshot into [onCategories] / [onItems] without reading the
     * file into a single String. Returns null if the file is missing or not for [sourceId].
     */
    fun readStreaming(
        dir: File,
        sourceId: String,
        onCategories: (List<Category>) -> Unit,
        onItems: (List<MediaItem>) -> Unit,
    ): Meta? {
        val f = File(dir, FILE_NAME)
        if (!f.isFile) return null
        return runCatching {
            f.reader(Charsets.UTF_8).buffered(16 * 1024).use { raw ->
                val reader = PushbackReader(raw, 1)
                parseSnapshot(reader, sourceId, onCategories, onItems)
            }
        }.getOrNull()
    }

    private fun parseSnapshot(
        reader: PushbackReader,
        expectedSourceId: String,
        onCategories: (List<Category>) -> Unit,
        onItems: (List<MediaItem>) -> Unit,
    ): Meta? {
        val first = JsonArrayStream.skipWs(reader)
        if (first < 0 || first.toChar() != '{') return null
        var sourceId: String? = null
        var savedAtMs = 0L
        var sawItems = false
        // Walk top-level keys. Order matches write(); still tolerate any order.
        while (true) {
            val c = JsonArrayStream.skipWs(reader)
            if (c < 0) return null
            val ch = c.toChar()
            if (ch == '}') break
            if (ch == ',') continue
            if (ch != '"') return null
            val key = readJsonString(reader, alreadyOpened = true) ?: return null
            val colon = JsonArrayStream.skipWs(reader)
            if (colon < 0 || colon.toChar() != ':') return null
            when (key) {
                "sourceId" -> {
                    val q = JsonArrayStream.skipWs(reader)
                    if (q < 0 || q.toChar() != '"') return null
                    sourceId = readJsonString(reader, alreadyOpened = true) ?: return null
                    if (sourceId != expectedSourceId) return null
                }
                "savedAtMs" -> {
                    savedAtMs = readJsonNumber(reader) ?: return null
                }
                "categories" -> {
                    val cats = ArrayList<Category>(32)
                    val res = JsonArrayStream.consume(reader, json, Category.serializer(), batchSize = 64) {
                        cats.addAll(it)
                    }
                    if (res.kind != JsonArrayStream.Kind.Array) return null
                    onCategories(cats)
                }
                "items" -> {
                    sawItems = true
                    val res = JsonArrayStream.consume(reader, json, MediaItem.serializer(), batchSize = ITEM_BATCH, onBatch = onItems)
                    if (res.kind != JsonArrayStream.Kind.Array) return null
                }
                else -> if (!skipJsonValue(reader)) return null
            }
        }
        if (sourceId == null || !sawItems) return null
        return Meta(sourceId, savedAtMs)
    }

    private fun readJsonString(reader: PushbackReader, alreadyOpened: Boolean): String? {
        val buf = StringBuilder()
        if (!alreadyOpened) {
            val q = JsonArrayStream.skipWs(reader)
            if (q < 0 || q.toChar() != '"') return null
        }
        var escape = false
        while (true) {
            val c = reader.read()
            if (c < 0) return null
            val ch = c.toChar()
            if (escape) {
                buf.append(when (ch) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    '"' -> '"'
                    '\\' -> '\\'
                    '/' -> '/'
                    'b' -> '\b'
                    'f' -> '\u000C'
                    else -> ch
                })
                escape = false
            } else when (ch) {
                '\\' -> escape = true
                '"' -> return buf.toString()
                else -> buf.append(ch)
            }
        }
    }

    private fun readJsonNumber(reader: PushbackReader): Long? {
        val first = JsonArrayStream.skipWs(reader)
        if (first < 0) return null
        val buf = StringBuilder()
        buf.append(first.toChar())
        while (true) {
            val c = reader.read()
            if (c < 0) break
            val ch = c.toChar()
            if (ch.isDigit() || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E') {
                buf.append(ch)
            } else {
                reader.unread(c)
                break
            }
        }
        return buf.toString().toLongOrNull()
    }

    private fun skipJsonValue(reader: PushbackReader): Boolean {
        val first = JsonArrayStream.skipWs(reader)
        if (first < 0) return false
        return when (first.toChar()) {
            '"' -> readJsonString(reader, alreadyOpened = true) != null
            '{' -> JsonArrayStream.readObjectInto(reader, first, StringBuilder(), maxChars = 1_000_000)
            '[' -> {
                // Reuse the object-array walker by pushing back and consuming as raw text depth.
                skipJsonArray(reader, first)
            }
            else -> {
                // number / true / false / null
                var c = first
                while (c >= 0) {
                    val ch = c.toChar()
                    if (ch.isWhitespace() || ch == ',' || ch == '}' || ch == ']') {
                        reader.unread(c)
                        break
                    }
                    c = reader.read()
                }
                true
            }
        }
    }

    private fun skipJsonArray(reader: PushbackReader, first: Int): Boolean {
        if (first.toChar() != '[') return false
        var depth = 1
        var inString = false
        var escape = false
        while (true) {
            val c = reader.read()
            if (c < 0) return false
            val ch = c.toChar()
            if (inString) {
                when {
                    escape -> escape = false
                    ch == '\\' -> escape = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '[', '{' -> depth++
                    ']', '}' -> {
                        depth--
                        if (depth == 0) return true
                    }
                }
            }
        }
    }

    fun clear(dir: File) {
        File(dir, FILE_NAME).delete()
        File(dir, "$FILE_NAME.tmp").delete()
    }

    /** Merge rule: only accept a fresh list that actually has items. */
    fun shouldReplace(newItems: List<MediaItem>): Boolean = newItems.isNotEmpty()
}
