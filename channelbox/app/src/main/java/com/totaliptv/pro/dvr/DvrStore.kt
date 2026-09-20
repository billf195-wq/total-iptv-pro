package com.totaliptv.pro.dvr

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText

class DvrStore(private val dir: Path) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val file: Path = dir.resolve("recordings.json")
    private val tmp: Path = dir.resolve("recordings.json.tmp")

    fun load(): DvrFile {
        return try {
            if (!file.exists()) return DvrFile()
            json.decodeFromString<DvrFile>(file.readText())
        } catch (_: Exception) {
            DvrFile()
        }
    }

    fun save(data: DvrFile) {
        Files.createDirectories(dir)
        Files.writeString(tmp, json.encodeToString(data))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
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
