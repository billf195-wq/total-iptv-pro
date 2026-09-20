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
 */
object StreamPlayer {
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

    internal fun vlcCommand(binary: String, urls: List<String>): List<String> {
        // --no-one-instance: default one-instance/D-Bus mode exits the new process
        // immediately (or never honors --play-and-exit), so next-episode never fires.
        val args = mutableListOf(
            binary,
            "--fullscreen",
            "--play-and-exit",
            "--no-one-instance",
            "--no-playlist-enqueue",
            "--meta-title=Total IPTV Pro"
        )
        if (urls.size == 1) {
            args += urls.first()
        } else {
            args += writeM3u(urls).absolutePath
        }
        return args
    }

    internal fun mpvCommand(binary: String, urls: List<String>): List<String> =
        listOf(binary, "--fullscreen", "--force-window=yes", "--title=Total IPTV Pro") + urls

    internal fun ffplayCommand(binary: String, url: String): List<String> =
        listOf(binary, "-fs", "-autoexit", "-window_title", "Total IPTV Pro", url)

    internal fun writeM3u(urls: List<String>): File {
        val dir = AppPaths.configDir.toFile()
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "series-next.m3u")
        val body = buildString {
            appendLine("#EXTM3U")
            urls.forEach { url ->
                appendLine("#EXTINF:-1,Total IPTV Pro")
                appendLine(url)
            }
        }
        Files.writeString(file.toPath(), body)
        return file
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
