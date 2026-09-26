package com.totaliptv.pro.dvr

import com.totaliptv.pro.ui.player.PlayerStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android live capture: download Xtream HLS (`.m3u8`) segments or a `.ts` body
 * onto this device. No ffmpeg-kit — OkHttp is already in the app.
 */
object DvrCapture {
    /** Unique HLS segment URLs remembered during one capture. Older ones drop off. */
    const val MAX_SEEN_SEGMENTS = 400

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun isHlsUrl(url: String): Boolean {
        val path = try {
            URI(url.trim()).path.orEmpty()
        } catch (_: Exception) {
            url
        }.lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    /**
     * Live catalog URLs are `.m3u8`. The player records the MPEG-TS `.ts` form
     * instead: many providers answer the playlist URL with an empty body or a
     * short window, which left a 0-byte file.
     */
    fun recordUrl(url: String): String {
        val trimmed = url.trim()
        val path = trimmed.substringBefore('?')
        if (path.contains("/live/", ignoreCase = true) && path.endsWith(".m3u8", ignoreCase = true)) {
            val ts = path.dropLast(".m3u8".length) + ".ts"
            return if (trimmed.contains('?')) "$ts?${trimmed.substringAfter('?')}" else ts
        }
        return trimmed
    }

    fun capture(url: String, output: File, stopFlag: AtomicBoolean) {
        output.parentFile?.mkdirs()
        val primary = recordUrl(url)
        captureOnce(primary, output, stopFlag)
        if (output.length() <= 0L && primary != url.trim() && !stopFlag.get()) {
            captureOnce(url, output, stopFlag)
        }
        if (output.length() <= 0L) error("No video saved")
    }

    private fun captureOnce(url: String, output: File, stopFlag: AtomicBoolean) {
        val req = Request.Builder().url(url).header("User-Agent", PlayerStream.STREAM_USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            val body = resp.body ?: return
            val headBytes = resp.peekBody(16).bytes()
            val headText = headBytes.toString(Charsets.ISO_8859_1).trimStart()
            val playlist = isHlsUrl(url) || headText.startsWith("#EXTM3U", ignoreCase = true)
            if (playlist) {
                writeHls(url, body.string(), output, stopFlag)
                return
            }
            val looksLikeTs = headBytes.isNotEmpty() && headBytes[0] == 0x47.toByte()
            val wantsTs = url.substringBefore('?').endsWith(".ts", ignoreCase = true)
            if (wantsTs && !looksLikeTs) return
            writeStream(body.byteStream(), output, stopFlag)
        }
    }

    private fun writeHls(playlistUrl: String, firstBody: String, output: File, stopFlag: AtomicBoolean) {
        var nextUrl = playlistUrl
        var first: String? = firstBody
        val seen = linkedSetOf<String>()
        RandomAccessFile(output, "rw").use { raf ->
            raf.seek(raf.length())
            while (!stopFlag.get() && !Thread.currentThread().isInterrupted) {
                val body = first ?: fetchText(nextUrl) ?: break
                first = null
                val parsed = parsePlaylistUris(body, nextUrl)
                if (parsed.master && parsed.uris.isNotEmpty()) {
                    nextUrl = parsed.uris.first()
                    continue
                }
                if (parsed.uris.isEmpty()) break
                var wrote = false
                for (seg in parsed.uris) {
                    if (stopFlag.get()) break
                    if (!rememberSegment(seen, seg)) continue
                    val bytes = fetchBytes(seg) ?: continue
                    raf.write(bytes)
                    wrote = true
                }
                if (parsed.ended) break
                val waitMs = if (wrote) parsed.targetDurationSec * 1000L else 1500L
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    data class PlaylistParse(
        val master: Boolean,
        val uris: List<String>,
        val targetDurationSec: Int,
        val ended: Boolean
    )

    fun parsePlaylistUris(body: String, playlistUrl: String): PlaylistParse {
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }
        val uris = mutableListOf<String>()
        var targetDuration = 2
        for (line in lines) {
            if (line.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true)) {
                targetDuration = line.substringAfter(':').trim().toIntOrNull()?.coerceIn(1, 30) ?: 2
            }
            if (line.startsWith("#")) continue
            uris += resolveRelative(playlistUrl, line)
        }
        val ended = lines.any { it.equals("#EXT-X-ENDLIST", ignoreCase = true) }
        return PlaylistParse(isMaster, uris, targetDuration, ended)
    }

    /**
     * Remember [segment] unless it was already seen. When the set grows past [max],
     * the oldest URLs are dropped so a long live recording cannot keep every segment.
     * Returns false when this segment should be skipped.
     */
    fun rememberSegment(seen: MutableSet<String>, segment: String, max: Int = MAX_SEEN_SEGMENTS): Boolean {
        if (!seen.add(segment)) return false
        val extra = seen.size - max
        if (extra > 0) {
            val it = seen.iterator()
            var dropped = 0
            while (it.hasNext() && dropped < extra) {
                it.next()
                it.remove()
                dropped++
            }
        }
        return true
    }

    fun resolveRelative(baseUrl: String, ref: String): String {
        val trimmed = ref.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        return try {
            URI(baseUrl).resolve(trimmed).toString()
        } catch (_: Exception) {
            val slash = baseUrl.lastIndexOf('/')
            if (slash > 8) baseUrl.substring(0, slash + 1) + trimmed else trimmed
        }
    }

    private fun writeStream(input: java.io.InputStream, output: File, stopFlag: AtomicBoolean) {
        output.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (!stopFlag.get()) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) out.write(buf, 0, n)
            }
        }
    }

    private fun fetchText(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", PlayerStream.STREAM_USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }.getOrNull()

    private fun fetchBytes(url: String): ByteArray? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", PlayerStream.STREAM_USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    }.getOrNull()
}
