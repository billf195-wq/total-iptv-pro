package com.totaliptv.pro.dvr

import android.content.Context
import android.os.Environment
import com.totaliptv.pro.TotalIptvProApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DvrRecorder(
    private val appContext: Context
) {
    data class Snapshot(
        val active: RecordingEntry?,
        val recordings: List<RecordingEntry>,
        val schedules: List<ScheduledRecording>,
        val recordingsDir: String
    )

    private val store = DvrStore(Path.of(DvrPaths.metadataDir(appContext.filesDir.absolutePath)))
    private val activeRef = AtomicReference<Active?>(null)
    private val schedulerStarted = AtomicBoolean(false)
    private val _snapshot = MutableStateFlow(readSnapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot
    @Volatile
    var lastMessage: String? = null
        private set
    @Volatile
    var overrideDir: String = ""
        set(value) {
            field = value
            publish()
        }

    private data class Active(
        val entry: RecordingEntry,
        val stopFlag: AtomicBoolean,
        val thread: Thread
    )

    fun recordingsDir(): File {
        val externalMovies = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.absolutePath
        val path = DvrPaths.resolveRecordingsDir(
            override = overrideDir,
            filesDir = appContext.filesDir.absolutePath,
            externalMoviesDir = externalMovies
        )
        return File(path).apply { mkdirs() }
    }

    fun isRecording(): Boolean = activeRef.get() != null

    fun startNow(
        channelName: String,
        title: String,
        streamUrl: String,
        channelId: String? = null,
        scheduledEndMs: Long? = null
    ): RecordingEntry {
        if (streamUrl.isBlank()) error("No stream URL to record")
        if (activeRef.get() != null) error("Already recording on this device — stop it first")
        val now = System.currentTimeMillis()
        val dir = recordingsDir()
        val file = File(dir, DvrPaths.recordingFileName(channelName, title, now))
        val stop = AtomicBoolean(false)
        val entry = RecordingEntry(
            id = UUID.randomUUID().toString(),
            channelName = channelName,
            title = title.ifBlank { channelName },
            startMs = now,
            filePath = file.absolutePath,
            streamUrl = streamUrl,
            channelId = channelId,
            status = RecordingStatus.RECORDING.name,
            scheduledEndMs = scheduledEndMs,
            captureEngine = "hls"
        )
        store.upsert(entry)
        val thread = Thread({
            try {
                DvrCapture.capture(streamUrl, file, stop)
            } finally {
                finishActive(stopped = stop.get())
            }
        }, "dvr-android").apply {
            isDaemon = true
            start()
        }
        activeRef.set(Active(entry, stop, thread))
        lastMessage = "Recording ${entry.title} on this device"
        publish()
        DvrRecordingService.start(appContext, entry.title)
        ensureScheduler()
        return entry
    }

    fun stop(): RecordingEntry? {
        val current = activeRef.get() ?: return null
        current.stopFlag.set(true)
        current.thread.interrupt()
        lastMessage = "Stopping recording…"
        return current.entry
    }

    fun schedule(
        channelName: String,
        title: String,
        streamUrl: String,
        startMs: Long,
        endMs: Long,
        channelId: String? = null
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
            channelId = channelId
        )
        store.addSchedule(item)
        lastMessage = "Scheduled ${item.title}"
        publish()
        ensureScheduler()
        return item
    }

    fun cancelSchedule(id: String) {
        store.removeSchedule(id)
        lastMessage = "Cancelled scheduled recording"
        publish()
    }

    fun deleteRecording(id: String) {
        val entry = store.recordings().find { it.id == id }
        store.removeRecording(id)
        if (entry != null && entry.filePath.isNotBlank()) {
            runCatching { File(entry.filePath).delete() }
        }
        lastMessage = "Deleted recording"
        publish()
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
        }, "dvr-android-sched").apply {
            isDaemon = true
            start()
        }
    }

    internal fun tickScheduler(nowMs: Long = System.currentTimeMillis()) {
        val current = activeRef.get()
        if (current != null && DvrSchedule.shouldStop(nowMs, current.entry.scheduledEndMs)) {
            stop()
        }
        if (activeRef.get() != null) return
        val due = store.schedules().firstOrNull { DvrSchedule.isDue(nowMs, it.startMs, it.endMs) }
        if (due != null) {
            store.removeSchedule(due.id)
            runCatching {
                startNow(due.channelName, due.title, due.streamUrl, due.channelId, due.endMs)
            }.onFailure { lastMessage = it.message }
            publish()
            return
        }
        store.schedules().filter { DvrSchedule.isExpired(nowMs, it.endMs) }.forEach {
            store.removeSchedule(it.id)
        }
        publish()
    }

    private fun finishActive(stopped: Boolean) {
        val current = activeRef.getAndSet(null) ?: return
        val file = File(current.entry.filePath)
        val size = if (file.exists()) file.length() else 0L
        val now = System.currentTimeMillis()
        val status = when {
            stopped -> RecordingStatus.STOPPED
            size > 0L -> RecordingStatus.COMPLETED
            else -> RecordingStatus.FAILED
        }
        store.upsert(
            current.entry.copy(
                durationMs = (now - current.entry.startMs).coerceAtLeast(0L),
                status = status.name,
                errorMessage = if (status == RecordingStatus.FAILED) "No data written" else null
            )
        )
        lastMessage = when (status) {
            RecordingStatus.COMPLETED -> "Saved ${current.entry.title}"
            RecordingStatus.STOPPED -> "Stopped ${current.entry.title}"
            else -> "Recording failed (empty file)"
        }
        DvrRecordingService.stop(appContext)
        publish()
    }

    private fun readSnapshot(): Snapshot {
        val data = store.load()
        return Snapshot(
            active = activeRef.get()?.entry,
            recordings = data.recordings.sortedByDescending { it.startMs },
            schedules = data.schedules.sortedBy { it.startMs },
            recordingsDir = recordingsDir().absolutePath
        )
    }

    private fun publish() {
        _snapshot.value = readSnapshot()
    }

    companion object {
        fun from(context: Context): DvrRecorder {
            val app = context.applicationContext as TotalIptvProApp
            return app.dvr
        }
    }
}
