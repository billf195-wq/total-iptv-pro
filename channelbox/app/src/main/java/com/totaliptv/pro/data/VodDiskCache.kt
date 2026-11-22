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
    /** 1.4.91-1.4.114 combined movies + series file. Read and migrated, never written. */
    const val FILE_NAME = "vod-catalog-cache.json"
    /** 1.4.115: one last-good file per kind, so a failed or empty load of one kind never touches the other. */
    const val MOVIES_FILE = "vod-movies-cache.json"
    const val SERIES_FILE = "vod-series-cache.json"
    const val ITEM_BATCH = 500
    private const val TMP_PREFIX = "vodcache-"
    private const val TMP_SUFFIX = ".tmp"

    @Serializable
    data class Snapshot(
        val sourceId: String,
        val savedAtMs: Long,
        val categories: List<Category>,
        val items: List<MediaItem>
    )

    data class Meta(val sourceId: String, val savedAtMs: Long)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val commitLock = Any()

    fun fileFor(dir: File, kind: ContentKind): File = File(
        dir,
        when (kind) {
            ContentKind.SERIES -> SERIES_FILE
            ContentKind.VOD -> MOVIES_FILE
            ContentKind.LIVE -> error("Live is never cached here")
        }
    )

    /**
     * Save each VOD kind found in [items] to its own file. A kind with no items is skipped,
     * so an empty list can never overwrite a good one.
     */
    fun write(dir: File, sourceId: String, categories: List<Category>, items: List<MediaItem>, nowMs: Long = System.currentTimeMillis()) {
        for (kind in VOD_KINDS) writeKind(dir, sourceId, kind, categories, items, nowMs)
    }

    /** Returns true when a non-empty [kind] list was committed. */
    fun writeKind(
        dir: File,
        sourceId: String,
        kind: ContentKind,
        categories: List<Category>,
        items: List<MediaItem>,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (items.none { it.kind == kind }) return false
        val w = Writer(dir, sourceId, kind, categories, nowMs)
        return try {
            w.add(items)
            w.commit()
        } catch (t: Throwable) {
            w.abort()
            throw t
        }
    }

    val VOD_KINDS = listOf(ContentKind.VOD, ContentKind.SERIES)

    /**
     * Incremental writer for one [kind] while streaming a network catalog. Each writer has its
     * own temp file, so two loads (a refresh while one is still downloading) never share or
     * truncate each other's file. [commit] replaces the last good file only when at least one
     * title of [kind] was written.
     */
    class Writer(
        dir: File,
        private val sourceId: String,
        val kind: ContentKind,
        categories: List<Category>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        private val dest = fileFor(dir, kind)
        private val tmp: File
        private val out: OutputStreamWriter
        var count = 0
            private set
        private var open = true

        init {
            require(kind != ContentKind.LIVE)
            dir.mkdirs()
            tmp = File.createTempFile(TMP_PREFIX + kind.name.lowercase() + "-", TMP_SUFFIX, dir)
            out = tmp.outputStream().buffered(16 * 1024).writer(Charsets.UTF_8)
            out.write("{\"sourceId\":")
            out.write(json.encodeToString(sourceId))
            out.write(",\"savedAtMs\":")
            out.write(nowMs.toString())
            out.write(",\"categories\":")
            out.write(json.encodeToString(categories.filter { it.kind == kind }))
            out.write(",\"items\":[")
        }

        fun add(items: List<MediaItem>) {
            if (!open) return
            for (item in items) {
                if (item.kind != kind) continue
                if (count > 0) out.write(",")
                out.write(json.encodeToString(MediaItem.serializer(), item))
                count++
            }
        }

        /** Atomically replace the last good [kind] file. Returns false (and keeps the old file) when empty. */
        fun commit(): Boolean {
            if (!open) return false
            open = false
            return try {
                out.write("]}")
                out.flush()
                out.close()
                if (count == 0) {
                    tmp.delete()
                    false
                } else synchronized(commitLock) {
                    if (!tmp.renameTo(dest)) {
                        // Windows-style rename-over failure: copy then swap; never leave dest missing.
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
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

    /**
     * Split a 1.4.91-1.4.114 combined cache into the per-kind files (streamed, one batch at a
     * time). A kind that already has its own file is left alone. The old file is removed only
     * after every kind it held is safely in its own file. Also removes temp files left by a
     * crash. Call once at startup, before any load starts.
     */
    fun migrateLegacy(dir: File): Boolean {
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith(TMP_PREFIX) && f.name.endsWith(TMP_SUFFIX)) f.delete()
        }
        File(dir, "$FILE_NAME.tmp").delete()
        val legacy = File(dir, FILE_NAME)
        if (!legacy.isFile) return false
        val legacySource = peekSourceId(legacy) ?: run {
            // Unreadable: keep it untouched for readKind's fallback rather than lose data.
            return false
        }
        var ok = true
        for (kind in VOD_KINDS) {
            if (readKindFile(fileFor(dir, kind), legacySource, kind, {}, {}) != null) continue
            val cats = ArrayList<Category>()
            var writer: Writer? = null
            // categories come before items in the file, so [cats] is filled when the writer opens.
            val meta = readFile(legacy, legacySource, { c -> cats.addAll(c.filter { it.kind == kind }) }) { batch ->
                val keep = batch.filter { it.kind == kind }
                if (keep.isEmpty()) return@readFile
                if (writer == null) writer = Writer(dir, legacySource, kind, cats)
                writer?.add(keep)
            }
            if (meta == null) { writer?.abort(); ok = false; continue }
            val w = writer ?: continue // legacy had none of this kind
            if (!w.commit()) ok = false
        }
        if (ok) legacy.delete()
        return ok
    }

    private fun peekSourceId(f: File): String? = runCatching {
        f.reader(Charsets.UTF_8).buffered(4096).use { raw ->
            val reader = PushbackReader(raw, 1)
            val first = JsonArrayStream.skipWs(reader)
            if (first < 0 || first.toChar() != '{') return@use null
            // sourceId is always the first key written.
            val q = JsonArrayStream.skipWs(reader)
            if (q < 0 || q.toChar() != '"') return@use null
            val key = readJsonString(reader, alreadyOpened = true)
            if (key != "sourceId") return@use null
            val colon = JsonArrayStream.skipWs(reader)
            if (colon < 0 || colon.toChar() != ':') return@use null
            readJsonString(reader, alreadyOpened = false)
        }
    }.getOrNull()

    /**
     * Stream the last good [kind] list: its own file first, else the pre-1.4.115 combined
     * file. Returns null when neither has a valid list for [sourceId].
     */
    fun readKind(
        dir: File,
        sourceId: String,
        kind: ContentKind,
        onCategories: (List<Category>) -> Unit,
        onItems: (List<MediaItem>) -> Unit,
    ): Meta? {
        readKindFile(fileFor(dir, kind), sourceId, kind, onCategories, onItems)?.let { return it }
        var n = 0
        val cats = ArrayList<Category>()
        val meta = readFile(File(dir, FILE_NAME), sourceId, { c -> cats.addAll(c.filter { it.kind == kind }) }) { batch ->
            val keep = batch.filter { it.kind == kind }
            if (keep.isNotEmpty()) {
                n += keep.size
                onItems(keep)
            }
        } ?: return null
        if (n == 0) return null
        onCategories(cats)
        return meta
    }

    private fun readKindFile(
        f: File,
        sourceId: String,
        kind: ContentKind,
        onCategories: (List<Category>) -> Unit,
        onItems: (List<MediaItem>) -> Unit,
    ): Meta? {
        if (!f.isFile) return null
        // Items are delivered as they stream. If the file breaks after some were delivered,
        // still report success so the caller never also reads the old file (duplicates).
        var n = 0
        val cats = ArrayList<Category>()
        val meta = readFile(f, sourceId, { c -> cats.addAll(c.filter { it.kind == kind }) }) { batch ->
            val keep = batch.filter { it.kind == kind }
            if (keep.isNotEmpty()) {
                n += keep.size
                onItems(keep)
            }
        }
        if (n == 0) return null
        onCategories(cats)
        return meta ?: Meta(sourceId, 0L)
    }

    /** Both kinds' last good lists (tests / tools). Null when neither kind has a list. */
    fun read(dir: File, sourceId: String): Snapshot? {
        val cats = ArrayList<Category>()
        val items = ArrayList<MediaItem>()
        var savedAt = 0L
        var any = false
        for (kind in VOD_KINDS) {
            val m = readKind(dir, sourceId, kind, { cats.addAll(it) }, { items.addAll(it) }) ?: continue
            any = true
            savedAt = maxOf(savedAt, m.savedAtMs)
        }
        if (!any) return null
        return Snapshot(sourceId, savedAt, cats, items)
    }

    /**
     * Stream one snapshot file into [onCategories] / [onItems] without reading it into a single
     * String. Returns null if the file is missing, unreadable, or not for [sourceId].
     */
    private fun readFile(
        f: File,
        sourceId: String,
        onCategories: (List<Category>) -> Unit,
        onItems: (List<MediaItem>) -> Unit,
    ): Meta? {
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
        for (kind in VOD_KINDS) fileFor(dir, kind).delete()
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith(TMP_PREFIX) && f.name.endsWith(TMP_SUFFIX)) f.delete()
        }
    }

    /** Merge rule: only accept a fresh list that actually has items. */
    fun shouldReplace(newItems: List<MediaItem>): Boolean = newItems.isNotEmpty()
}
