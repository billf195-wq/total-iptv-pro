package com.totaliptv.pro.dvr

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class DvrStore(private val dir: File) {
    constructor(dirPath: String) : this(File(dirPath))

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val file = File(dir, "recordings.json")
    private val tmp = File(dir, "recordings.json.tmp")

    fun load(): DvrFile {
        return try {
            if (!file.exists()) return DvrFile()
            json.decodeFromString<DvrFile>(file.readText())
        } catch (_: Exception) {
            DvrFile()
        }
    }

    fun save(data: DvrFile) {
        dir.mkdirs()
        tmp.writeText(json.encodeToString(data))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun recordings(): List<RecordingEntry> = load().recordings.sortedByDescending { it.startMs }

    fun schedules(): List<ScheduledRecording> = load().schedules.sortedBy { it.startMs }

    fun upsert(entry: RecordingEntry): List<RecordingEntry> {
        val data = load()
        val next = listOf(entry) + data.recordings.filterNot { it.id == entry.id }
        save(data.copy(recordings = next))
        return next.sortedByDescending { it.startMs }
    }

    fun removeRecording(id: String): DvrFile {
        val data = load()
        val next = data.copy(recordings = data.recordings.filterNot { it.id == id })
        save(next)
        return next
    }

    fun addSchedule(schedule: ScheduledRecording): List<ScheduledRecording> {
        val data = load()
        val next = listOf(schedule) + data.schedules.filterNot { it.id == schedule.id }
        save(data.copy(schedules = next))
        return next.sortedBy { it.startMs }
    }

    fun removeSchedule(id: String): List<ScheduledRecording> {
        val data = load()
        val next = data.schedules.filterNot { it.id == id }
        save(data.copy(schedules = next))
        return next.sortedBy { it.startMs }
    }
}
