package com.totaliptv.pro.desktop.player

import com.totaliptv.pro.desktop.data.MediaItem
import com.totaliptv.pro.desktop.util.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Plays streams via an external player (Linux / Windows).
 * Prefers configured player, else VLC → mpv → ffplay.
 *
 * Series (1.2.9, Linux + Windows): always start **one** episode URL. AppRoot
 * sequential-plays SxxE(n+1) when that process exits after a real play
 * (≥20s, exit 0). VLC’s own playlist Next will not advance (one-item playlist).
 *
 * Live (1.2.10): VLC/mpv/ffplay omit play-and-exit / autoexit so a valid HLS
 * live window stays up. VOD still uses `--play-and-exit` for sequential next.
 *
 * Windows VLC extra (1.2.2): `taskkill` leftover `vlc.exe` and `--ignore-config`
 * so installer vlcrc one-instance cannot steal the launch.
 *
 * Audio (1.2.15): VLC `--audio-language=eng,en,english` and mpv
 * `--alang=eng,en,english` prefer English when the stream has several tracks.
 * ffplay has no reliable CLI language pick without extra probing — leave as-is.
 *
 * Supported Next: in-app Next, always-on-top Next, Ctrl+Right / Media Next
 * (OS-wide on Windows; when this app is focused on Linux). Auto-advance at EOF.
 */
enum class SplitSide { LEFT, RIGHT }

data class SplitSession(
    val leftItem: MediaItem,
    val rightItem: MediaItem,
    var activeAudio: SplitSide = SplitSide.LEFT,
    var leftProcess: Process? = null,
    var rightProcess: Process? = null,
    val leftRcPort: Int = 4212,
    val rightRcPort: Int = 4213,
    val player: String = "vlc"
)

object StreamPlayer {
    const val SPLIT_NEEDS_VLC =
        "Game Day split screen needs VLC so you can switch which side has audio. Install VLC and try again."

    /** VLC language preference order when multiple audio tracks exist. */
    internal const val VLC_AUDIO_LANGUAGE = "--audio-language=eng,en,english"
    /** mpv language preference order when multiple audio tracks exist. */
    internal const val MPV_AUDIO_LANGUAGE = "--alang=eng,en,english"
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

    @Volatile
    var splitSession: SplitSession? = null
        private set

    @Volatile
    private var progressJob: Job? = null

    private const val PROGRESS_RC_PORT = 4214

    /** Windows Quit: kill every player image we launch, not only the last binary. */
    internal val WINDOWS_QUIT_IMAGES: List<String> = listOf("vlc.exe", "mpv.exe", "ffplay.exe")

    fun play(
        url: String,
        preferredPlayer: String = "auto",
        live: Boolean = false,
        fullscreen: Boolean = true
    ): String = playQueue(listOf(url), preferredPlayer, live, fullscreen = fullscreen)

    /**
     * Play one or more URLs. VLC/mpv always launch a **single** URL (sequential
     * next lives in AppRoot). ffplay is one file.
     *
     * [live] omits VLC `--play-and-exit` / ffplay `-autoexit` so a valid HLS
     * window is not treated as a finished VOD item (Windows 1.2.9 died in 5–12s).
     *
     * [startPositionSeconds] is applied only when a single URL is launched.
     * A later episode in a queue must not inherit `--start-time`.
     */
    fun playQueue(
        urls: List<String>,
        preferredPlayer: String = "auto",
        live: Boolean = false,
        startPositionSeconds: Long? = null,
        scope: CoroutineScope? = null,
        onProgress: ((positionMs: Long, durationMs: Long?, percent: Int?) -> Unit)? = null,
        fullscreen: Boolean = true
    ): String {
        val clean = urls.map { it.trim() }.filter { it.isNotBlank() }
        require(clean.isNotEmpty()) { "No stream URL to play" }
        stoppedByUser = false
        progressJob?.cancel()
        progressJob = null
        stopSplitScreen()
        stopProcessOnly()
        val treatLive = live || isLiveStreamUrl(clean.first())
        val resumeAt = if (treatLive) null else startPositionSeconds
        val resolved = resolvePlayerCommand(clean, preferredPlayer, treatLive, resumeAt, fullscreen)
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
        val proc = ProcessBuilder(resolved.command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        current = proc
        if (onProgress != null && scope != null && !treatLive && resolved.command.first().contains("vlc", ignoreCase = true)) {
            progressJob = scope.launch(Dispatchers.IO) {
                delay(2000)
                while (proc.isAlive) {
                    val prog = VlcControl.queryProgress(PROGRESS_RC_PORT)
                    if (prog != null && prog.totalLengthSeconds > 0) {
                        onProgress(
                            prog.currentTimeSeconds * 1000L,
                            prog.totalLengthSeconds * 1000L,
                            prog.percent
                        )
                    }
                    delay(2000)
                }
            }
        }
        return resolved.command.first()
    }

    fun isSplitActive(): Boolean {
        val session = splitSession ?: return false
        return session.leftProcess?.isAlive == true || session.rightProcess?.isAlive == true
    }

    /**
     * Two live pictures side by side. Audio switching uses VLC's remote-control
     * interface, so mpv and ffplay are not used (ffplay would start one side
     * with audio stripped and could not unmute it).
     */
    fun playSplitScreen(
        scope: CoroutineScope,
        left: MediaItem,
        right: MediaItem,
        preferredPlayer: String = "auto",
        initialAudio: SplitSide = SplitSide.LEFT
    ): SplitSession {
        require(left.streamUrl.isNotBlank()) { "Left channel stream URL is empty" }
        require(right.streamUrl.isNotBlank()) { "Right channel stream URL is empty" }
        val vlc = resolveVlcBinary() ?: error(SPLIT_NEEDS_VLC)
        if (preferredPlayer.trim().lowercase() == "mpv" || preferredPlayer.trim().lowercase() == "ffplay") {
            // Still require VLC. The preferred player cannot switch audio after launch.
            if (resolveVlcBinary() == null) error(SPLIT_NEEDS_VLC)
        }
        stop()
        stoppedByUser = false

        val bounds = WindowPositioner.getPrimaryScreenBounds()
        val halfWidth = (bounds.width / 2).coerceAtLeast(320)
        val fullHeight = bounds.height.coerceAtLeast(240)
        val xLeft = bounds.x
        val xRight = bounds.x + halfWidth
        val y = bounds.y

        fun sideCommand(item: MediaItem, x: Int, port: Int, title: String): List<String> {
            val args = mutableListOf(vlc)
            if (AppPaths.isWindows) args += "--ignore-config"
            args += "--no-one-instance"
            args += "--no-playlist-enqueue"
            args += "--no-video-title-show"
            args += "--no-qt-video-autoresize"
            args += VLC_AUDIO_LANGUAGE
            args += "--width=$halfWidth"
            args += "--height=$fullHeight"
            args += "--video-x=$x"
            args += "--video-y=$y"
            args += "--extraintf=rc"
            args += "--rc-host=127.0.0.1:$port"
            args += "--rc-quiet"
            args += "--meta-title=$title"
            args += item.streamUrl
            return args
        }

        val leftCmd = sideCommand(left, xLeft, 4212, "Total IPTV Pro — Left")
        val rightCmd = sideCommand(right, xRight, 4213, "Total IPTV Pro — Right")
        val leftProc = ProcessBuilder(leftCmd)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        val rightProc = ProcessBuilder(rightCmd)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        val session = SplitSession(
            leftItem = left,
            rightItem = right,
            activeAudio = initialAudio,
            leftProcess = leftProc,
            rightProcess = rightProc
        )
        splitSession = session
        WindowPositioner.snapWindowAsync(scope, leftProc, leftProc.pid(), xLeft, y, halfWidth, fullHeight)
        WindowPositioner.snapWindowAsync(scope, rightProc, rightProc.pid(), xRight, y, halfWidth, fullHeight)
        scope.launch(Dispatchers.IO) {
            for (waitMs in listOf(500L, 1200L, 2000L, 3000L)) {
                delay(waitMs)
                applySplitVolumes(session)
            }
        }
        return session
    }

    fun switchSplitAudio(side: SplitSide) {
        val session = splitSession ?: return
        session.activeAudio = side
        applySplitVolumes(session)
    }

    private fun applySplitVolumes(session: SplitSession) {
        if (session.activeAudio == SplitSide.LEFT) {
            VlcControl.setVolume(session.leftRcPort, 256)
            VlcControl.setVolume(session.rightRcPort, 0)
        } else {
            VlcControl.setVolume(session.leftRcPort, 0)
            VlcControl.setVolume(session.rightRcPort, 256)
        }
    }

    fun stopSplitScreen() {
        val session = splitSession ?: return
        VlcControl.stopPlayer(session.leftRcPort)
        VlcControl.stopPlayer(session.rightRcPort)
        session.leftProcess?.destroyForcibly()
        session.rightProcess?.destroyForcibly()
        splitSession = null
    }

    fun stop() {
        stoppedByUser = true
        progressJob?.cancel()
        progressJob = null
        stopProcessOnly()
        stopSplitScreen()
        if (AppPaths.isWindows) {
            killWindowsPlayerTree(currentBinaryHint())
        }
    }

    private fun stopProcessOnly() {
        current?.destroyForcibly()
        current = null
    }

    private fun currentBinaryHint(): String? = lastBinary

    fun isPlaying(): Boolean = current?.isAlive == true || isSplitActive()

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

    /**
     * Xtream live is `{host}/live/{user}/{pass}/{id}.m3u8`. HLS without
     * `/movie/` or `/series/` is treated as live so `--play-and-exit` is skipped
     * even if the caller forgot the [live] flag.
     */
    internal fun isLiveStreamUrl(url: String): Boolean {
        val raw = url.trim()
        if (raw.isBlank()) return false
        val path = try {
            java.net.URI(raw).path.orEmpty()
        } catch (_: Exception) {
            raw
        }.lowercase()
        if (path.contains("/live/")) return true
        val hls = path.endsWith(".m3u8") || path.endsWith(".m3u")
        return hls && !path.contains("/movie/") && !path.contains("/series/")
    }

    internal fun resolvePlayerCommand(
        urls: List<String>,
        preferred: String,
        live: Boolean = false,
        startPositionSeconds: Long? = null,
        fullscreen: Boolean = true
    ): ResolvedCommand? {
        val pref = preferred.trim().lowercase()
        val ordered = when (pref) {
            "vlc" -> listOf("vlc", "mpv", "ffplay")
            "mpv" -> listOf("mpv", "vlc", "ffplay")
            "ffplay" -> listOf("ffplay", "vlc", "mpv")
            else -> listOf("vlc", "mpv", "ffplay")
        }
        val treatLive = live || isLiveStreamUrl(urls.firstOrNull().orEmpty())
        for (name in ordered) {
            commandFor(
                name,
                urls,
                live = treatLive,
                startPositionSeconds = startPositionSeconds,
                fullscreen = fullscreen
            )?.let { return it }
        }
        return null
    }

    internal fun commandFor(
        name: String,
        urls: List<String>,
        windows: Boolean = AppPaths.isWindows,
        live: Boolean = false,
        startPositionSeconds: Long? = null,
        fullscreen: Boolean = true
    ): ResolvedCommand? {
        return when (name) {
            "vlc" -> {
                val vlc = resolveVlcBinary() ?: return null
                ResolvedCommand(
                    vlcCommand(vlc, urls, windows, live, startPositionSeconds, fullscreen),
                    playlist = treatsLaunchAsPlaylist("vlc", urls.size, windows)
                )
            }
            "mpv" -> {
                val bin = resolveOnPath("mpv") ?: return null
                ResolvedCommand(
                    mpvCommand(bin, urls, windows, live, startPositionSeconds, fullscreen),
                    playlist = treatsLaunchAsPlaylist("mpv", urls.size, windows)
                )
            }
            "ffplay" -> {
                val bin = resolveOnPath("ffplay") ?: return null
                ResolvedCommand(
                    ffplayCommand(bin, urls.first(), live, startPositionSeconds, fullscreen),
                    playlist = false
                )
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
     * **First URL only** on Linux and Windows.
     * VOD/series: `--play-and-exit` + `--no-repeat` so the process ends at EOF
     * and AppRoot can auto-advance.
     * Live HLS: do **not** pass `--play-and-exit`. Windows VLC treats a sliding
     * live window (often 5–12s of segments) as a finished item and quits 0.
     * Windows also uses `--ignore-config` so installer vlcrc one-instance cannot
     * override `--no-one-instance` (1.2.1 still replayed the same episode).
     */
    internal fun vlcCommand(
        binary: String,
        urls: List<String>,
        windows: Boolean = AppPaths.isWindows,
        live: Boolean = false,
        startPositionSeconds: Long? = null,
        fullscreen: Boolean = true
    ): List<String> {
        val args = mutableListOf(binary)
        if (windows) {
            args += "--ignore-config"
        }
        if (fullscreen) args += "--fullscreen"
        args += VLC_AUDIO_LANGUAGE
        if (!live) {
            args += "--play-and-exit"
            args += "--extraintf=rc"
            args += "--rc-host=127.0.0.1:$PROGRESS_RC_PORT"
            args += "--rc-quiet"
        }
        args += "--no-one-instance"
        args += "--no-playlist-enqueue"
        args += "--no-repeat"
        args += "--no-loop"
        if (windows) {
            args += "--no-one-instance-when-started-from-file"
            args += "--no-started-from-file"
        }
        if (live) {
            args += "--network-caching=3000"
            args += "--live-caching=3000"
            args += "--http-reconnect"
        }
        // --start-time applies to every item in a VLC playlist. Only the resumed URL gets it.
        if (urls.size == 1 && startPositionSeconds != null && startPositionSeconds > 0) {
            args += "--start-time=$startPositionSeconds"
        }
        args += "--meta-title=Total IPTV Pro"
        args += urls.first()
        return args
    }

    @Suppress("UNUSED_PARAMETER")
    internal fun mpvCommand(
        binary: String,
        urls: List<String>,
        windows: Boolean = AppPaths.isWindows,
        live: Boolean = false,
        startPositionSeconds: Long? = null,
        fullscreen: Boolean = true
    ): List<String> {
        val args = mutableListOf(binary, "--force-window=yes", "--title=Total IPTV Pro")
        if (fullscreen) args += "--fullscreen"
        args += MPV_AUDIO_LANGUAGE
        args += "--loop-file=no"
        args += "--loop-playlist=no"
        if (live) {
            args += "--keep-open=yes"
        } else {
            args += "--keep-open=no"
        }
        if (urls.size == 1 && startPositionSeconds != null && startPositionSeconds > 0) {
            args += "--start=$startPositionSeconds"
        }
        args += urls.first()
        return args
    }

    /**
     * ffplay has no stable `-ast` / language preference that works across
     * HLS + VOD without probing tracks first. Leave the argv unchanged so
     * live/VOD play-and-exit behavior stays intact.
     */
    internal fun ffplayCommand(
        binary: String,
        url: String,
        live: Boolean = false,
        startPositionSeconds: Long? = null,
        fullscreen: Boolean = true
    ): List<String> {
        val args = mutableListOf(binary)
        if (fullscreen) args += "-fs"
        if (!live) args += "-autoexit"
        args += "-window_title"
        args += "Total IPTV Pro"
        if (startPositionSeconds != null && startPositionSeconds > 0) {
            args += "-ss"
            args += startPositionSeconds.toString()
        }
        args += url
        return args
    }

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
