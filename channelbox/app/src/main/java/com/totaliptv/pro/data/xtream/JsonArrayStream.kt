package com.totaliptv.pro.data.xtream

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import java.io.PushbackReader
import java.io.Reader

/**
 * Streams a JSON array of objects from a Reader, decoding one object at a time.
 * Never materializes the full payload string or the full decoded list.
 * A truncated tail is ignored; objects already decoded are kept.
 */
object JsonArrayStream {
    const val DEFAULT_BATCH = 500
    const val AUTH_PREFIX_MAX = 4096
    /**
     * 1.4.115: how far to look past a UTF-8 BOM or a PHP notice/warning printed before the
     * JSON (1.4.113 found the first '[' anywhere in the body; 1.4.114 gave up and returned 0).
     */
    const val JUNK_PREFIX_MAX = 256 * 1024

    enum class Kind { Empty, Array, Object, Other }

    data class Result(
        val count: Int,
        val kind: Kind,
        val prefix: String? = null,
        /** Characters skipped before the JSON started (BOM, PHP notices). */
        val skipped: Int = 0,
        /** Objects that were complete JSON but did not decode (field shape mismatch). */
        val rejected: Int = 0,
    )

    fun <T> consume(
        reader: Reader,
        json: Json,
        deserializer: DeserializationStrategy<T>,
        batchSize: Int = DEFAULT_BATCH,
        onBatch: (List<T>) -> Unit,
    ): Result {
        val pb = reader as? PushbackReader ?: PushbackReader(reader, 1)
        var first = skipWs(pb)
        if (first < 0) return Result(0, Kind.Empty)
        var skipped = 0
        // Skip a byte-order mark and any text (PHP notice, HTML warning) before the JSON.
        while (first >= 0 && first.toChar() != '[' && first.toChar() != '{') {
            if (skipped >= JUNK_PREFIX_MAX) return Result(0, Kind.Other, skipped = skipped)
            skipped++
            first = pb.read()
        }
        if (first < 0) return Result(0, if (skipped == 0) Kind.Empty else Kind.Other, skipped = skipped)
        val start = first.toChar()
        if (start == '{') {
            return Result(0, Kind.Object, prefix = readLimitedObject(pb, first, AUTH_PREFIX_MAX), skipped = skipped)
        }
        val size = batchSize.coerceAtLeast(1)
        var total = 0
        var rejected = 0
        val batch = ArrayList<T>(size)
        val buf = StringBuilder(512)
        while (true) {
            val c = skipWs(pb)
            if (c < 0) break
            val ch = c.toChar()
            if (ch == ']') break
            if (ch == ',') continue
            var objStart = c
            if (ch != '{') {
                var x = c
                while (x >= 0) {
                    val xc = x.toChar()
                    if (xc == ']') return flush(batch, total, onBatch, skipped, rejected)
                    if (xc == '{') {
                        objStart = x
                        break
                    }
                    x = pb.read()
                }
                if (x < 0) break
            }
            buf.setLength(0)
            if (!readObjectInto(pb, objStart, buf)) break
            runCatching { json.decodeFromString(deserializer, buf.toString()) }
                .onSuccess { batch.add(it) }
                .onFailure { rejected++ }
            if (batch.size >= size) {
                onBatch(ArrayList(batch))
                total += batch.size
                batch.clear()
            }
        }
        return flush(batch, total, onBatch, skipped, rejected)
    }

    private fun <T> flush(batch: ArrayList<T>, total: Int, onBatch: (List<T>) -> Unit, skipped: Int, rejected: Int): Result {
        var n = total
        if (batch.isNotEmpty()) {
            onBatch(ArrayList(batch))
            n += batch.size
            batch.clear()
        }
        return Result(n, Kind.Array, skipped = skipped, rejected = rejected)
    }

    fun skipWs(reader: PushbackReader): Int {
        while (true) {
            val c = reader.read()
            if (c < 0) return -1
            if (!c.toChar().isWhitespace()) return c
        }
    }

    /**
     * [first] is the already-read '{'. Reads the object (or up to [maxChars])
     * so a compact auth/error payload can be inspected without slurping a huge body.
     */
    fun readLimitedObject(reader: PushbackReader, first: Int, maxChars: Int): String {
        val buf = StringBuilder(minOf(maxChars, 256))
        readObjectInto(reader, first, buf, maxChars)
        return buf.toString()
    }

    /**
     * Append one JSON object to [buf], starting with [first] (the '{').
     * Returns false if the stream ends before the object is closed.
     */
    fun readObjectInto(
        reader: PushbackReader,
        first: Int,
        buf: StringBuilder,
        maxChars: Int = Int.MAX_VALUE,
    ): Boolean {
        buf.append(first.toChar())
        var depth = 1
        var inString = false
        var escape = false
        while (buf.length < maxChars) {
            val c = reader.read()
            if (c < 0) return false
            val ch = c.toChar()
            buf.append(ch)
            if (inString) {
                when {
                    escape -> escape = false
                    ch == '\\' -> escape = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return true
                    }
                }
            }
        }
        return depth == 0
    }
}
