package com.totaliptv.pro.desktop.player

import com.totaliptv.pro.desktop.util.AppPaths
import java.io.File

/**
 * Plays streams via an external player (Linux / Windows).
 * Prefers configured player, else VLC → mpv → ffplay.
 *
 * Series (1.2.9, Linux + Windows): always start **one** episode URL. AppRoot
 * sequential-plays SxxE(n+1) when that process exits after a real play
 * (≥20s, exit 0). VLC’s own playlist Next will not advance (one-item playlist).
 *
 * Windows VLC extra (1.2.2): `taskkill` leftover `vlc.exe` and `--ignore-config`
 * so installer vlcrc one-instance cannot steal the launch.
 *
 * Supported Next: in-app Next, always-on-top Next, Ctrl+Right / Media Next
 * (OS-wide on Windows; when this app is focused on Linux). Auto-advance at EOF.
 */
object StreamPlayer {
    /** Brief pause after taskkill so Windows releases VLC's one-instance mutex. */
    internal const val WINDOWS_KILL_SETTLE_MS = 200
    @Volatile
    private var current: Process? = null
    @Volatile
    private var lastBinary: String? = null
    @Volatile
    private var stoppedByUser: Boolean = false
    @Volatile
    var lastLaunchWasPlaylist: Boolean = false
        private set
    @Volatile
    var lastLaunchAtMs: Long = 0L
        private set
    @Volatile
    var lastExitCode: Int? = null
        private set
    @Volatile
    var lastPlaybackDurationMs: Long = 0L
        private set

    /** Windows Quit: kill every player image we launch, not only the last binary. */
    internal val WINDOWS_QUIT_IMAGES: List<String> = listOf("vlc.exe", "mpv.exe", "ffplay.exe")

    fun play(url: String, preferredPlayer: String = "auto"): String =
        playQueue(listOf(url), preferredPlayer)

    /**
     * Play one or more URLs. VLC/mpv always launch a **single** URL (sequential
     * next lives in AppRoot). ffplay is one file.
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
        lastBinary = resolved.command.first()
        if (AppPaths.isWindows) {
            killWindowsPlayerTree(resolved.command.first())
        }
        markLaunch()
        current = ProcessBuilder(resolved.command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        return resolved.command.first()
    }

    fun stop() {
        stoppedByUser = true
        stopProcessOnly()
        if (AppPaths.isWindows) {
            killWindowsPlayerTree(currentBinaryHint())
        }
    }

    private fun stopProcessOnly() {
        current?.destroyForcibly()
        current = null
    }

    private fun currentBinaryHint(): String? = lastBinary

    fun isPlaying(): Boolean = current?.isAlive == true

    /**
     * Block until the current player process exits.
     * @return true if the process ended on its own (ready for next episode).
     */
    fun waitForExit(): Boolean {
        val proc = current ?: return false
        return try {
            val code = proc.waitFor()
            markExit(code)
            val natural = !stoppedByUser
            if (current === proc) current = null
            natural
        } catch (_: InterruptedException) {
            false
        }
    }

    internal fun markLaunch(nowMs: Long = System.currentTimeMillis()) {
        lastLaunchAtMs = nowMs
        lastExitCode = null
        lastPlaybackDurationMs = 0L
    }

    internal fun markExit(exitCode: Int, nowMs: Long = System.currentTimeMillis()) {
        lastExitCode = exitCode
        lastPlaybackDurationMs = (nowMs - lastLaunchAtMs).coerceAtLeast(0L)
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

    internal fun commandFor(
        name: String,
        urls: List<String>,
        windows: Boolean = AppPaths.isWindows
    ): ResolvedCommand? {
        return when (name) {
            "vlc" -> {
                val vlc = resolveVlcBinary() ?: return null
                ResolvedCommand(
                    vlcCommand(vlc, urls, windows),
                    playlist = treatsLaunchAsPlaylist("vlc", urls.size, windows)
                )
            }
            "mpv" -> {
                val bin = resolveOnPath("mpv") ?: return null
                ResolvedCommand(
                    mpvCommand(bin, urls, windows),
                    playlist = treatsLaunchAsPlaylist("mpv", urls.size, windows)
                )
            }
            "ffplay" -> {
                val bin = resolveOnPath("ffplay") ?: return null
                ResolvedCommand(ffplayCommand(bin, urls.first()), playlist = false)
            }
            else -> null
        }
    }

    /**
     * Never a playlist: AppRoot starts SxxE(n+1) after a natural player exit on
     * both Linux and Windows. Kept as a function so tests lock the contract.
     */
    @Suppress("UNUSED_PARAMETER")
    internal fun treatsLaunchAsPlaylist(player: String, urlCount: Int, windows: Boolean): Boolean = false

    /**
     * **First URL only** on Linux and Windows. `--play-and-exit` + `--no-repeat`
     * so the process ends at EOF and AppRoot can auto-advance.
     * Windows also uses `--ignore-config` so installer vlcrc one-instance cannot
     * override `--no-one-instance` (1.2.1 still replayed the same episode).
     */
    internal fun vlcCommand(
        binary: String,
        urls: List<String>,
        windows: Boolean = AppPaths.isWindows
    ): List<String> {
        val args = mutableListOf(binary)
        if (windows) {
            args += "--ignore-config"
        }
        args += "--fullscreen"
        args += "--play-and-exit"
        args += "--no-one-instance"
        args += "--no-playlist-enqueue"
        args += "--no-repeat"
        args += "--no-loop"
        if (windows) {
            args += "--no-one-instance-when-started-from-file"
            args += "--no-started-from-file"
        }
        args += "--meta-title=Total IPTV Pro"
        args += urls.first()
        return args
    }

    @Suppress("UNUSED_PARAMETER")
    internal fun mpvCommand(
        binary: String,
        urls: List<String>,
        windows: Boolean = AppPaths.isWindows
    ): List<String> {
        val args = mutableListOf(binary, "--fullscreen", "--force-window=yes", "--title=Total IPTV Pro")
        args += "--loop-file=no"
        args += "--loop-playlist=no"
        args += "--keep-open=no"
        args += urls.first()
        return args
    }

    internal fun ffplayCommand(binary: String, url: String): List<String> =
        listOf(binary, "-fs", "-autoexit", "-window_title", "Total IPTV Pro", url)

    internal fun windowsKillImageNames(binary: String): List<String> {
        val raw = File(binary).name.ifBlank { binary }
        val base = raw.removeSuffix(".exe").removeSuffix(".EXE").lowercase()
        return when {
            base.contains("vlc") -> listOf("vlc.exe")
            base.contains("mpv") -> listOf("mpv.exe")
            base.contains("ffplay") -> listOf("ffplay.exe")
            raw.endsWith(".exe", ignoreCase = true) -> listOf(raw)
            else -> listOf("$raw.exe")
        }
    }

    internal fun windowsKillCommand(imageName: String): List<String> =
        listOf("taskkill.exe", "/F", "/T", "/IM", imageName)

    internal fun windowsQuitImageNames(extraBinary: String? = null): List<String> {
        val images = linkedSetOf<String>()
        images += WINDOWS_QUIT_IMAGES
        extraBinary?.let { images += windowsKillImageNames(it) }
        return images.toList()
    }

    /** taskkill /F /T the VLC/mpv/ffplay tree so Quit leaves zero player processes. */
    internal fun killWindowsPlayerTree(extraBinary: String? = null) {
        for (image in windowsQuitImageNames(extraBinary)) {
            runCatching {
                ProcessBuilder(windowsKillCommand(image))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor()
            }
        }
        runCatching { Thread.sleep(WINDOWS_KILL_SETTLE_MS.toLong()) }
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
