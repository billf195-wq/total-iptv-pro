package com.totaliptv.pro.desktop.dvr

import com.totaliptv.pro.desktop.data.PreferencesStore
import com.totaliptv.pro.desktop.util.AppPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One concurrent personal recording on this PC. Status + metadata stay local.
 */
object DvrRecorder {
    data class ActiveRecording(
        val entry: RecordingEntry,
        val session: DvrCapture.Session,
        val stopFlag: AtomicBoolean
    )

    data class Snapshot(
        val active: RecordingEntry?,
        val recordings: List<RecordingEntry>,
        val schedules: List<ScheduledRecording>,
        val recordingsDir: String,
        val engineHint: String
    )

    private val store = DvrStore(AppPaths.configDir)
    private val activeRef = AtomicReference<ActiveRecording?>(null)
    private val schedulerStarted = AtomicBoolean(false)
    @Volatile
    var lastMessage: String? = null
        private set

    fun store(): DvrStore = store

    fun snapshot(): Snapshot {
        val data = store.load()
        return Snapshot(
            active = activeRef.get()?.entry,
            recordings = data.recordings.sortedByDescending { it.startMs },
            schedules = data.schedules.sortedBy { it.startMs },
            recordingsDir = recordingsDir().toString(),
            engineHint = DvrCapture.fallbackHint()
        )
    }

    fun recordingsDir(): Path {
        val prefs = PreferencesStore.load()
        return DvrPaths.resolveRecordingsDir(
            override = prefs.recordingsDir,
            windows = AppPaths.isWindows,
            userHome = System.getProperty("user.home").orEmpty(),
            localAppData = System.getenv("LOCALAPPDATA"),
            videosDir = Path.of(System.getProperty("user.home").orEmpty(), "Videos").toString()
        )
    }

    fun isRecording(): Boolean = activeRef.get() != null

    fun startNow(
        channelName: String,
        title: String,
        streamUrl: String,
        channelId: String? = null,
        scheduledEndMs: Long? = null,
        contentKind: String = DvrKind.LIVE
    ): RecordingEntry {
        if (streamUrl.isBlank()) error("No stream URL to record")
        if (activeRef.get() != null) error("Already recording on this device — stop it first (one at a time)")
        val now = System.currentTimeMillis()
        val dir = recordingsDir()
        Files.createDirectories(dir)
        val kind = DvrKind.normalize(contentKind)
        val finite = DvrKind.isFiniteDownload(kind, streamUrl)
        val ext = DvrKind.extensionForUrl(streamUrl, kind)
        val file = dir.resolve(DvrPaths.recordingFileName(channelName, title, now, ext))
        val stop = AtomicBoolean(false)
        val durationSec = if (finite) {
            null
        } else {
            DvrSchedule.remainingMs(now, scheduledEndMs)?.let { ms ->
                (ms / 1000L).coerceAtLeast(1L)
            }
        }
        val session = DvrCapture.start(streamUrl, file, durationSec, stop, finite = finite)
        val entry = RecordingEntry(
            id = UUID.randomUUID().toString(),
            channelName = channelName,
            title = title.ifBlank { channelName },
            startMs = now,
            durationMs = 0L,
            filePath = file.toString(),
            streamUrl = streamUrl,
            channelId = channelId,
            status = RecordingStatus.RECORDING.name,
            scheduledEndMs = scheduledEndMs,
            captureEngine = session.engine.name,
            contentKind = kind
        )
        store.upsert(entry)
        activeRef.set(ActiveRecording(entry, session, stop))
        lastMessage = if (finite) {
            "Downloading ${entry.title} → ${file.fileName}"
        } else {
            "Recording ${entry.title} → ${file.fileName} (${session.engine.name})"
        }
        Thread({
            try {
                DvrCapture.waitFor(session, stop)
            } finally {
                finishActive(stopped = stop.get())
            }
        }, "dvr-wait").apply {
            isDaemon = true
            start()
        }
        ensureScheduler()
        return entry
    }

    fun stop(): RecordingEntry? {
        val current = activeRef.get() ?: return null
        current.stopFlag.set(true)
        DvrCapture.stop(current.session)
        lastMessage = "Stopping recording…"
        return current.entry
    }

    fun schedule(
        channelName: String,
        title: String,
        streamUrl: String,
        startMs: Long,
        endMs: Long,
        channelId: String? = null,
        contentKind: String = DvrKind.LIVE
    ): ScheduledRecording {
        if (streamUrl.isBlank()) error("No stream URL to schedule")
        if (endMs <= startMs) error("Schedule needs a later end time")
        val item = ScheduledRecording(
            id = UUID.randomUUID().toString(),
            channelName = channelName,
            title = title.ifBlank { channelName },
            streamUrl = streamUrl,
            startMs = startMs,
            endMs = endMs,
            channelId = channelId,
            contentKind = DvrKind.normalize(contentKind)
        )
        store.addSchedule(item)
        lastMessage = "Scheduled ${item.title} on ${item.channelName}"
        ensureScheduler()
        return item
    }

    fun cancelSchedule(id: String) {
        store.removeSchedule(id)
        lastMessage = "Cancelled scheduled recording"
    }

    fun deleteRecording(id: String, deleteFile: Boolean = true) {
        val entry = store.recordings().find { it.id == id }
        store.removeRecording(id)
        if (deleteFile && entry != null && entry.filePath.isNotBlank()) {
            runCatching { Files.deleteIfExists(Path.of(entry.filePath)) }
        }
        lastMessage = "Deleted recording"
    }

    fun ensureScheduler() {
        if (!schedulerStarted.compareAndSet(false, true)) return
        Thread({
            while (!Thread.currentThread().isInterrupted) {
                tickScheduler()
                try {
                    Thread.sleep(5_000L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "dvr-scheduler").apply {
            isDaemon = true
            start()
        }
    }

    internal fun tickScheduler(nowMs: Long = System.currentTimeMillis()) {
        val current = activeRef.get()
        val end = current?.entry?.scheduledEndMs
        if (current != null && DvrSchedule.shouldStop(nowMs, end)) {
            stop()
        }
        if (activeRef.get() != null) return
        val due = store.schedules().firstOrNull { DvrSchedule.isDue(nowMs, it.startMs, it.endMs) }
        if (due != null) {
            store.removeSchedule(due.id)
            runCatching {
                startNow(
                    channelName = due.channelName,
                    title = due.title,
                    streamUrl = due.streamUrl,
                    channelId = due.channelId,
                    scheduledEndMs = due.endMs,
                    contentKind = due.contentKind
                )
            }.onFailure { lastMessage = it.message }
            return
        }
        store.schedules().filter { DvrSchedule.isExpired(nowMs, it.endMs) }.forEach {
            store.removeSchedule(it.id)
        }
    }

    fun shutdown() {
        stop()
    }

    private fun finishActive(stopped: Boolean) {
        val current = activeRef.getAndSet(null) ?: return
        val file = Path.of(current.entry.filePath)
        val size = runCatching { Files.size(file) }.getOrDefault(0L)
        val now = System.currentTimeMillis()
        val duration = (now - current.entry.startMs).coerceAtLeast(0L)
        val status = when {
            stopped -> RecordingStatus.STOPPED
            size > 0L -> RecordingStatus.COMPLETED
            else -> RecordingStatus.FAILED
        }
        val updated = current.entry.copy(
            durationMs = duration,
            status = status.name,
            errorMessage = if (status == RecordingStatus.FAILED) "No data written" else null
        )
        store.upsert(updated)
        lastMessage = when (status) {
            RecordingStatus.COMPLETED -> "Saved ${updated.title}"
            RecordingStatus.STOPPED -> "Stopped ${updated.title}"
            else -> "Recording failed (empty file)"
        }
    }
}
