package com.totaliptv.pro.dvr

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

    fun capture(url: String, output: File, stopFlag: AtomicBoolean) {
        output.parentFile?.mkdirs()
        if (!isHlsUrl(url)) {
            streamBody(url, output, stopFlag)
            return
        }
        var playlistUrl = url
        val seen = linkedSetOf<String>()
        RandomAccessFile(output, "rw").use { raf ->
            raf.seek(raf.length())
            while (!stopFlag.get() && !Thread.currentThread().isInterrupted) {
                val body = fetchText(playlistUrl) ?: break
                val parsed = parsePlaylistUris(body, playlistUrl)
                if (parsed.master && parsed.uris.isNotEmpty()) {
                    playlistUrl = parsed.uris.first()
                    continue
                }
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

    private fun streamBody(url: String, output: File, stopFlag: AtomicBoolean) {
        val req = Request.Builder().url(url).header("User-Agent", "TotalIptvPro-DVR").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} downloading stream")
            val body = resp.body ?: return
            output.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                val input = body.byteStream()
                while (!stopFlag.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) out.write(buf, 0, n)
                }
            }
        }
    }

    private fun fetchText(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "TotalIptvPro-DVR").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }.getOrNull()

    private fun fetchBytes(url: String): ByteArray? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "TotalIptvPro-DVR").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    }.getOrNull()
}
