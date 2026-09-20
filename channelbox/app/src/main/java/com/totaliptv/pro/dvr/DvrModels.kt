package com.totaliptv.pro.dvr

import kotlinx.serialization.Serializable

enum class RecordingStatus {
    SCHEDULED,
    RECORDING,
    COMPLETED,
    FAILED,
    STOPPED
}

@Serializable
data class RecordingEntry(
    val id: String,
    val channelName: String,
    val title: String,
    val startMs: Long,
    val durationMs: Long = 0L,
    val filePath: String = "",
    val streamUrl: String = "",
    val channelId: String? = null,
    val status: String = RecordingStatus.COMPLETED.name,
    val scheduledEndMs: Long? = null,
    val errorMessage: String? = null,
    val captureEngine: String? = null,
    /** LIVE, VOD (movie), or SERIES. Unknown/blank reads as LIVE. */
    val contentKind: String = DvrKind.LIVE
) {
    fun statusEnum(): RecordingStatus =
        runCatching { RecordingStatus.valueOf(status) }.getOrDefault(RecordingStatus.COMPLETED)

    fun isActive(): Boolean = statusEnum() == RecordingStatus.RECORDING

    fun playable(): Boolean =
        filePath.isNotBlank() && statusEnum() in setOf(RecordingStatus.COMPLETED, RecordingStatus.STOPPED)
}

@Serializable
data class ScheduledRecording(
    val id: String,
    val channelName: String,
    val title: String,
    val streamUrl: String,
    val startMs: Long,
    val endMs: Long,
    val channelId: String? = null,
    val contentKind: String = DvrKind.LIVE
) {
    fun durationMs(): Long = (endMs - startMs).coerceAtLeast(0L)
}

@Serializable
data class DvrFile(
    val recordings: List<RecordingEntry> = emptyList(),
    val schedules: List<ScheduledRecording> = emptyList()
)

object DvrSchedule {
    const val LEAD_MS: Long = 15_000L

    fun shouldStart(nowMs: Long, startMs: Long, leadMs: Long = LEAD_MS): Boolean =
        nowMs >= startMs - leadMs

    fun shouldStop(nowMs: Long, endMs: Long?): Boolean =
        endMs != null && nowMs >= endMs

    fun remainingMs(nowMs: Long, endMs: Long?): Long? =
        endMs?.let { (it - nowMs).coerceAtLeast(0L) }

    fun isDue(nowMs: Long, startMs: Long, endMs: Long, leadMs: Long = LEAD_MS): Boolean =
        shouldStart(nowMs, startMs, leadMs) && nowMs < endMs

    fun isExpired(nowMs: Long, endMs: Long): Boolean = nowMs >= endMs
}
