package com.totaliptv.pro.desktop.player

import com.totaliptv.pro.desktop.util.AppPaths
import java.io.File

/**
 * Plays streams via an external player (Linux / Windows).
 * Prefers configured player, else VLC → mpv → ffplay.
 */
object StreamPlayer {
    @Volatile
    private var current: Process? = null
    @Volatile
    private var stoppedByUser: Boolean = false

    fun play(url: String, preferredPlayer: String = "auto"): String {
        stoppedByUser = false
        stopProcessOnly()
        val cmd = resolvePlayerCommand(url, preferredPlayer)
            ?: error(
                if (AppPaths.isWindows) {
                    "No media player found. Install VLC (recommended), or add mpv/ffplay to PATH."
                } else {
                    "No media player found. Install vlc, mpv, or ffmpeg (ffplay)."
                }
            )
        current = ProcessBuilder(cmd)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        return cmd.first()
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

    private fun resolvePlayerCommand(url: String, preferred: String): List<String>? {
        val pref = preferred.trim().lowercase()
        val ordered = when (pref) {
            "vlc" -> listOf("vlc", "mpv", "ffplay")
            "mpv" -> listOf("mpv", "vlc", "ffplay")
            "ffplay" -> listOf("ffplay", "vlc", "mpv")
            else -> listOf("vlc", "mpv", "ffplay")
        }
        for (name in ordered) {
            commandFor(name, url)?.let { return it }
        }
        return null
    }

    private fun commandFor(name: String, url: String): List<String>? {
        return when (name) {
            "vlc" -> {
                val vlc = resolveVlcBinary() ?: return null
                listOf(vlc, "--fullscreen", "--play-and-exit", "--meta-title=Total IPTV Pro", url)
            }
            "mpv" -> {
                val bin = resolveOnPath("mpv") ?: return null
                listOf(bin, "--fullscreen", "--force-window=yes", "--title=Total IPTV Pro", url)
            }
            "ffplay" -> {
                val bin = resolveOnPath("ffplay") ?: return null
                listOf(bin, "-fs", "-autoexit", "-window_title", "Total IPTV Pro", url)
            }
            else -> null
        }
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
