package com.totaliptv.pro.desktop.player

import com.totaliptv.pro.desktop.util.AppPaths
import java.io.File
import java.nio.file.Files

/**
 * Plays streams via an external player (Linux / Windows).
 * Prefers configured player, else VLC → mpv → ffplay.
 *
 * Series next-episode depends on handing the player a queue (not a single URL)
 * and disabling VLC's default one-instance mode so Linux/Windows actually
 * wait for / advance the playlist we launched.
 *
 * Linux VLC: remaining episodes as a local M3U (working reference).
 * Windows VLC: remaining episodes as separate CLI streams so Next advances;
 * M3U is only the CreateProcess-length fallback.
 */
object StreamPlayer {
    /** Stay under Windows CreateProcess 32,767-char limit with headroom for quoting. */
    internal const val WINDOWS_CMDLINE_SOFT_LIMIT = 24_000
    @Volatile
    private var current: Process? = null
    @Volatile
    private var stoppedByUser: Boolean = false
    @Volatile
    var lastLaunchWasPlaylist: Boolean = false
        private set

    fun play(url: String, preferredPlayer: String = "auto"): String =
        playQueue(listOf(url), preferredPlayer)

    /**
     * Play one or more URLs. VLC/mpv receive the full queue so Next / end-of-file
     * advance inside the player. ffplay only supports one file (caller may sequential-play).
     */
    fun playQueue(urls: List<String>, preferredPlayer: String = "auto"): String {
        val clean = urls.map { it.trim() }.filter { it.isNotBlank() }
        require(clean.isNotEmpty()) { "No stream URL to play" }
        stoppedByUser = false
        stopProcessOnly()
        val resolved = resolvePlayerCommand(clean, preferredPlayer)
            ?: error(
                if (AppPaths.isWindows) {
                    "No media player found. Install VLC (recommended), or add mpv/ffplay to PATH."
                } else {
                    "No media player found. Install vlc, mpv, or ffmpeg (ffplay)."
                }
            )
        lastLaunchWasPlaylist = resolved.playlist
        current = ProcessBuilder(resolved.command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        return resolved.command.first()
    }

    fun stop() {
        stoppedByUser = true
        stopProcessOnly()
    }

    private fun stopProcessOnly() {
        current?.destroyForcibly()
        current = null
    }

    fun isPlaying(): Boolean = current?.isAlive == true

    /**
     * Block until the current player process exits.
     * @return true if the process ended on its own (ready for next episode).
     */
    fun waitForExit(): Boolean {
        val proc = current ?: return false
        return try {
            proc.waitFor()
            val natural = !stoppedByUser
            if (current === proc) current = null
            natural
        } catch (_: InterruptedException) {
            false
        }
    }

    fun availablePlayers(): List<String> {
        val found = mutableListOf<String>()
        if (resolveVlcBinary() != null) found += "vlc"
        if (commandExists("mpv") || commandExists("mpv.exe")) found += "mpv"
        if (commandExists("ffplay") || commandExists("ffplay.exe")) found += "ffplay"
        return found
    }

    internal data class ResolvedCommand(
        val command: List<String>,
        val playlist: Boolean
    )

    internal fun resolvePlayerCommand(urls: List<String>, preferred: String): ResolvedCommand? {
        val pref = preferred.trim().lowercase()
        val ordered = when (pref) {
            "vlc" -> listOf("vlc", "mpv", "ffplay")
            "mpv" -> listOf("mpv", "vlc", "ffplay")
            "ffplay" -> listOf("ffplay", "vlc", "mpv")
            else -> listOf("vlc", "mpv", "ffplay")
        }
        for (name in ordered) {
            commandFor(name, urls)?.let { return it }
        }
        return null
    }

    internal fun commandFor(name: String, urls: List<String>): ResolvedCommand? {
        return when (name) {
            "vlc" -> {
                val vlc = resolveVlcBinary() ?: return null
                ResolvedCommand(vlcCommand(vlc, urls), playlist = urls.size > 1)
            }
            "mpv" -> {
                val bin = resolveOnPath("mpv") ?: return null
                ResolvedCommand(mpvCommand(bin, urls), playlist = urls.size > 1)
            }
            "ffplay" -> {
                val bin = resolveOnPath("ffplay") ?: return null
                // ffplay plays a single file; remaining episodes are sequential in AppRoot.
                ResolvedCommand(ffplayCommand(bin, urls.first()), playlist = false)
            }
            else -> null
        }
    }

    /**
     * Linux keeps the 1.2.0 argv (M3U path for queues) — that path is the working reference.
     *
     * Windows VLC differs: installer/vlcrc often enables one-instance + enqueue, and
     * `--one-instance-when-started-from-file` defaults to *enabled*. Passing a local
     * `series-next.m3u` looks like a file-association launch, so the Qt instance may
     * keep the playlist file as a single item (recursive=collapse). Next then restarts
     * that item instead of the next episode.
     *
     * Windows therefore prefers the documented multi-stream argv form
     * (`vlc [options] [stream] ...` — streams are enqueued) and only writes an M3U
     * when the Windows CreateProcess limit would be exceeded.
     */
    internal fun vlcCommand(
        binary: String,
        urls: List<String>,
        windows: Boolean = AppPaths.isWindows
    ): List<String> {
        val args = mutableListOf(
            binary,
            "--fullscreen",
            "--play-and-exit",
            "--no-one-instance",
            "--no-playlist-enqueue"
        )
        if (windows) {
            args += "--no-one-instance-when-started-from-file"
            args += "--no-started-from-file"
            args += "--no-repeat"
            args += "--no-loop"
            args += "--recursive=expand"
        }
        args += "--meta-title=Total IPTV Pro"
        args += if (windows) windowsVlcInputs(args, urls) else linuxVlcInputs(urls)
        return args
    }

    internal fun mpvCommand(
        binary: String,
        urls: List<String>,
        windows: Boolean = AppPaths.isWindows
    ): List<String> {
        val args = mutableListOf(binary, "--fullscreen", "--force-window=yes", "--title=Total IPTV Pro")
        if (windows) {
            args += "--loop-file=no"
            args += "--loop-playlist=no"
        }
        args += urls
        return args
    }

    internal fun ffplayCommand(binary: String, url: String): List<String> =
        listOf(binary, "-fs", "-autoexit", "-window_title", "Total IPTV Pro", url)

    private fun linuxVlcInputs(urls: List<String>): List<String> =
        if (urls.size == 1) listOf(urls.first())
        else listOf(writeM3u(urls, windows = false).absolutePath)

    /**
     * ProcessBuilder argv (not cmd.exe) so `&` / `?` in Xtream URLs stay intact.
     * Soft-cap well under the 32,767-char Windows CreateProcess limit.
     */
    internal fun windowsVlcInputs(argsSoFar: List<String>, urls: List<String>): List<String> {
        if (urls.isEmpty()) return emptyList()
        if (urls.size == 1) return listOf(urls.first())
        val trial = argsSoFar + urls
        return if (estimateWindowsCmdlineLength(trial) < WINDOWS_CMDLINE_SOFT_LIMIT) {
            urls
        } else {
            listOf(fileToVlcMrl(writeM3u(urls, windows = true)))
        }
    }

    internal fun writeM3u(urls: List<String>, windows: Boolean = AppPaths.isWindows): File {
        val file = if (windows) {
            val dir = File(System.getProperty("java.io.tmpdir") ?: ".")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "total-iptv-pro-series-next.m3u")
        } else {
            val dir = AppPaths.configDir.toFile()
            if (!dir.exists()) dir.mkdirs()
            File(dir, "series-next.m3u")
        }
        val body = if (windows) {
            // CRLF + EXTINF:0 (not -1/live) + distinct titles so Windows demuxers
            // treat entries as separate VOD items. Linux M3U format is unchanged.
            buildString {
                append("#EXTM3U\r\n")
                urls.forEachIndexed { i, url ->
                    append("#EXTINF:0,Episode ${i + 1}\r\n")
                    append(url)
                    append("\r\n")
                }
            }
        } else {
            buildString {
                appendLine("#EXTM3U")
                urls.forEach { url ->
                    appendLine("#EXTINF:-1,Total IPTV Pro")
                    appendLine(url)
                }
            }
        }
        Files.writeString(file.toPath(), body)
        return file
    }

    /**
     * VLC MRLs use `/` and `file:///`; a raw `C:\...` path can be misread (`:` starts
     * an input option). Java's `file:/C:/...` is normalized to `file:///C:/...`.
     */
    internal fun fileToVlcMrl(file: File): String {
        val raw = file.absoluteFile.toURI().toString()
        return if (raw.startsWith("file:/") && !raw.startsWith("file://")) {
            "file://" + raw.removePrefix("file:")
        } else {
            raw
        }
    }

    internal fun estimateWindowsCmdlineLength(args: List<String>): Int {
        if (args.isEmpty()) return 0
        return args.sumOf { estimateWindowsArgLength(it) + 1 } - 1
    }

    private fun estimateWindowsArgLength(arg: String): Int {
        val needsQuotes = arg.any { it <= ' ' || it == '"' }
        var n = arg.length + arg.count { it == '"' }
        if (needsQuotes) n += 2
        return n
    }

    private fun resolveVlcBinary(): String? {
        resolveOnPath("vlc")?.let { return it }
        if (!AppPaths.isWindows) {
            if (File("/usr/bin/vlc").canExecute()) return "/usr/bin/vlc"
            return null
        }
        // Windows: where.exe, then common install locations
        whereOnWindows("vlc")?.let { return it }
        val candidates = listOf(
            File("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe"),
            File("C:\\Program Files (x86)\\VideoLAN\\VLC\\vlc.exe"),
            File(System.getenv("ProgramFiles") ?: "C:\\Program Files", "VideoLAN\\VLC\\vlc.exe"),
            File(System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)", "VideoLAN\\VLC\\vlc.exe"),
            File(System.getenv("LOCALAPPDATA") ?: "", "Programs\\VideoLAN\\VLC\\vlc.exe")
        )
        return candidates.firstOrNull { it.isFile }?.absolutePath
    }

    private fun resolveOnPath(name: String): String? {
        if (commandExists(name)) return name
        if (AppPaths.isWindows) {
            val withExe = if (name.endsWith(".exe", ignoreCase = true)) name else "$name.exe"
            if (commandExists(withExe)) return withExe
            whereOnWindows(name)?.let { return it }
        }
        return null
    }

    private fun whereOnWindows(name: String): String? {
        if (!AppPaths.isWindows) return null
        return try {
            val pb = ProcessBuilder("where.exe", name)
            pb.redirectError(ProcessBuilder.Redirect.DISCARD)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val code = proc.waitFor()
            if (code != 0 || out.isBlank()) null
            else out.lineSequence().firstOrNull { it.isNotBlank() && File(it.trim()).isFile }?.trim()
        } catch (_: Exception) {
            null
        }
    }

    private fun commandExists(name: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        return path.split(File.pathSeparator).any { dir ->
            if (dir.isBlank()) return@any false
            val f = File(dir, name)
            f.isFile && (f.canExecute() || AppPaths.isWindows)
        }
    }
}
