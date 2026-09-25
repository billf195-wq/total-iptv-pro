package com.totaliptv.pro.desktop.player

import com.totaliptv.pro.desktop.util.AppPaths
import java.awt.EventQueue
import java.io.File

/**
 * Finds ffmpeg for DVR. PATH checks are cheap. A WinGet package-folder walk is
 * not, and must never run on the Swing UI thread.
 */
object FfmpegLocator {
    @Volatile
    private var cached: String? = null

    @Volatile
    private var fullScanDone: Boolean = false

    fun cachedPath(): String? = cached?.takeIf { it.isNotBlank() }

    /** True only when the caller is allowed to walk WinGet / common install folders. */
    fun mayWalkFilesystem(onUiThread: Boolean): Boolean = !onUiThread

    fun onUiThread(): Boolean = EventQueue.isDispatchThread()

    /**
     * Full lookup. Returns the cached path immediately on the UI thread and
     * schedules the folder walk elsewhere.
     */
    fun resolveForCaller(): String? {
        cachedPath()?.let { return it }
        if (!mayWalkFilesystem(onUiThread())) {
            scheduleFullScan()
            return pathOnly()
        }
        return locate()
    }

    fun locate(): String? {
        if (!mayWalkFilesystem(onUiThread())) {
            scheduleFullScan()
            return cachedPath() ?: pathOnly()
        }
        synchronized(this) {
            if (fullScanDone) return cachedPath() ?: pathOnly()
            val found = pathOnly() ?: wingetScan() ?: commonPaths()
            cached = found
            fullScanDone = true
            return found
        }
    }

    private fun scheduleFullScan() {
        if (fullScanDone || scanScheduled) return
        synchronized(this) {
            if (fullScanDone || scanScheduled) return
            scanScheduled = true
        }
        Thread({
            try {
                locate()
            } finally {
                scanScheduled = false
            }
        }, "ffmpeg-locate").apply {
            isDaemon = true
            start()
        }
    }

    @Volatile
    private var scanScheduled: Boolean = false

    private fun pathOnly(): String? {
        if (commandOnPath("ffmpeg")) return "ffmpeg"
        if (AppPaths.isWindows && commandOnPath("ffmpeg.exe")) return "ffmpeg.exe"
        return null
    }

    private fun commandOnPath(name: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        return path.split(File.pathSeparator).any { dir ->
            if (dir.isBlank()) return@any false
            val f = File(dir, name)
            f.isFile && (f.canExecute() || AppPaths.isWindows)
        }
    }

    private fun wingetScan(): String? {
        if (!AppPaths.isWindows) return null
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return null
        val packages = File(home, "AppData/Local/Microsoft/WinGet/Packages")
        if (!packages.isDirectory) return null
        return packages.walkTopDown()
            .maxDepth(6)
            .firstOrNull { it.isFile && it.name.equals("ffmpeg.exe", ignoreCase = true) }
            ?.absolutePath
    }

    private fun commonPaths(): String? {
        if (!AppPaths.isWindows) return null
        val candidates = listOf(
            "C:\\ffmpeg\\bin\\ffmpeg.exe",
            "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe",
            "C:\\Program Files (x86)\\ffmpeg\\bin\\ffmpeg.exe"
        )
        return candidates.firstOrNull { File(it).isFile }
    }
}
