package com.totaliptv.pro.desktop.dvr

import com.totaliptv.pro.desktop.util.AppPaths
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live capture for Xtream `.m3u8` / `.ts` URLs on this machine.
 *
 * Preference: ffmpeg on PATH (copy MPEG-TS) → VLC sout file → HLS segment download.
 * Personal time-shift only — no redistribution, no DRM circumvention.
 */
object DvrCapture {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    data class Engine(
        val name: String,
        val kind: Kind
    ) {
        enum class Kind { FFMPEG, VLC, HLS }
    }

    data class Session(
        val process: Process?,
        val stopFlag: AtomicBoolean,
        val engine: Engine,
        val thread: Thread?
    )

    fun detectEngine(
        windows: Boolean = AppPaths.isWindows,
        ffmpegExists: Boolean = commandExists("ffmpeg") || (windows && commandExists("ffmpeg.exe")),
        vlcBinary: String? = resolveVlcBinary(windows)
    ): Engine {
        return when {
            ffmpegExists -> Engine("ffmpeg", Engine.Kind.FFMPEG)
            vlcBinary != null -> Engine("vlc", Engine.Kind.VLC)
            else -> Engine("hls", Engine.Kind.HLS)
        }
    }

    fun ffmpegCommand(binary: String, url: String, output: Path, durationSec: Long?): List<String> {
        val args = mutableListOf(
            binary, "-nostdin", "-hide_banner", "-loglevel", "error",
            "-y", "-i", url, "-c", "copy", "-bsf:v", "dump_extra", "-f", "mpegts"
        )
        if (durationSec != null && durationSec > 0L) {
            args += listOf("-t", durationSec.toString())
        }
        args += output.toString()
        return args
    }

    fun vlcCommand(binary: String, url: String, output: Path, durationSec: Long?, windows: Boolean): List<String> {
        val dst = output.toAbsolutePath().toString().replace('\\', '/')
        val args = mutableListOf(binary)
        if (windows) args += "--ignore-config"
        args += listOf("-I", "dummy", "--no-video-title-show", "--no-sout-all")
        args += url
        args += "--sout=#std{access=file,mux=ts,dst=$dst}"
        if (durationSec != null && durationSec > 0L) {
            args += "--run-time=$durationSec"
        }
        args += "vlc://quit"
        return args
    }

    fun isHlsUrl(url: String): Boolean {
        val path = try {
            URI(url.trim()).path.orEmpty()
        } catch (_: Exception) {
            url
        }.lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    fun start(
        url: String,
        output: Path,
        durationSec: Long?,
        stopFlag: AtomicBoolean,
        windows: Boolean = AppPaths.isWindows
    ): Session {
        Files.createDirectories(output.parent)
        val engine = detectEngine(windows)
        return when (engine.kind) {
            Engine.Kind.FFMPEG -> {
                val bin = resolveOnPath("ffmpeg") ?: if (windows) "ffmpeg.exe" else "ffmpeg"
                val proc = ProcessBuilder(ffmpegCommand(bin, url, output, durationSec))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                Session(proc, stopFlag, engine, null)
            }
            Engine.Kind.VLC -> {
                val bin = resolveVlcBinary(windows) ?: error("VLC not found")
                val proc = ProcessBuilder(vlcCommand(bin, url, output, durationSec, windows))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                Session(proc, stopFlag, engine, null)
            }
            Engine.Kind.HLS -> {
                val thread = Thread({
                    runCatching { captureHttp(url, output, stopFlag) }
                }, "dvr-hls").apply {
                    isDaemon = true
                    start()
                }
                Session(null, stopFlag, engine, thread)
            }
        }
    }

    fun stop(session: Session?) {
        session ?: return
        session.stopFlag.set(true)
        session.process?.destroyForcibly()
        session.thread?.interrupt()
    }

    fun waitFor(session: Session, extraStop: AtomicBoolean = session.stopFlag) {
        val proc = session.process
        if (proc != null) {
            while (proc.isAlive && !extraStop.get()) {
                runCatching { Thread.sleep(250) }
            }
            if (extraStop.get() && proc.isAlive) proc.destroyForcibly()
            runCatching { proc.waitFor() }
            return
        }
        session.thread?.join()
    }

    fun fallbackHint(windows: Boolean = AppPaths.isWindows): String {
        val engine = detectEngine(windows)
        return when (engine.kind) {
            Engine.Kind.FFMPEG -> "Recording with ffmpeg (copy MPEG-TS)."
            Engine.Kind.VLC -> "ffmpeg not on PATH — recording with VLC file output."
            Engine.Kind.HLS -> {
                if (windows) {
                    "ffmpeg/VLC not found — downloading HLS/.ts segments. Install ffmpeg (or VLC) for more reliable capture."
                } else {
                    "ffmpeg/VLC not found — downloading HLS/.ts segments. Install ffmpeg (sudo apt install ffmpeg) for more reliable capture."
                }
            }
        }
    }

    internal fun parsePlaylistUris(body: String, playlistUrl: String): PlaylistParse {
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

    data class PlaylistParse(
        val master: Boolean,
        val uris: List<String>,
        val targetDurationSec: Int,
        val ended: Boolean
    )

    internal fun resolveRelative(baseUrl: String, ref: String): String {
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

    private fun captureHttp(url: String, output: Path, stopFlag: AtomicBoolean) {
        Files.createDirectories(output.parent)
        if (!isHlsUrl(url)) {
            streamBody(url, output, stopFlag)
            return
        }
        var playlistUrl = url
        val seen = linkedSetOf<String>()
        RandomAccessFile(output.toFile(), "rw").use { raf ->
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
                    if (!seen.add(seg)) continue
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

    private fun streamBody(url: String, output: Path, stopFlag: AtomicBoolean) {
        val req = Request.Builder().url(url).header("User-Agent", "TotalIptvPro-DVR").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} downloading stream")
            val body = resp.body ?: return
            Files.newOutputStream(output).use { out ->
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

    private fun fetchText(url: String): String? {
        return runCatching {
            val req = Request.Builder().url(url).header("User-Agent", "TotalIptvPro-DVR").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()
    }

    private fun fetchBytes(url: String): ByteArray? {
        return runCatching {
            val req = Request.Builder().url(url).header("User-Agent", "TotalIptvPro-DVR").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.bytes()
            }
        }.getOrNull()
    }

    private fun resolveOnPath(name: String): String? {
        if (commandExists(name)) return name
        if (AppPaths.isWindows) {
            val exe = if (name.endsWith(".exe", ignoreCase = true)) name else "$name.exe"
            if (commandExists(exe)) return exe
        }
        return null
    }

    internal fun commandExists(name: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        return path.split(File.pathSeparator).any { dir ->
            if (dir.isBlank()) return@any false
            val f = File(dir, name)
            f.isFile && (f.canExecute() || AppPaths.isWindows)
        }
    }

    internal fun resolveVlcBinary(windows: Boolean = AppPaths.isWindows): String? {
        if (commandExists("vlc")) return "vlc"
        if (windows && commandExists("vlc.exe")) return "vlc.exe"
        if (!windows) {
            if (File("/usr/bin/vlc").canExecute()) return "/usr/bin/vlc"
            if (File("/usr/bin/cvlc").canExecute()) return "/usr/bin/cvlc"
            return null
        }
        val candidates = listOf(
            File("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe"),
            File("C:\\Program Files (x86)\\VideoLAN\\VLC\\vlc.exe"),
            File(System.getenv("ProgramFiles") ?: "C:\\Program Files", "VideoLAN\\VLC\\vlc.exe"),
            File(System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)", "VideoLAN\\VLC\\vlc.exe")
        )
        return candidates.firstOrNull { it.isFile }?.absolutePath
    }
}
