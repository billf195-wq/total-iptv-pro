package com.totaliptv.pro.dvr

/**
 * A recording left as "recording" after the app died is not an active job.
 * Show it: playable if any video was saved, failed if the file is empty.
 */
object RecordingReconcile {
    fun interrupted(
        entries: List<RecordingEntry>,
        activeId: String?,
        lengthOf: (String) -> Long
    ): List<RecordingEntry> {
        return entries.mapNotNull { entry ->
            if (entry.statusEnum() != RecordingStatus.RECORDING) return@mapNotNull null
            if (activeId != null && entry.id == activeId) return@mapNotNull null
            val bytes = runCatching { lengthOf(entry.filePath) }.getOrDefault(0L)
            if (bytes > 0L) {
                entry.copy(status = RecordingStatus.STOPPED.name, errorMessage = "Interrupted")
            } else {
                entry.copy(status = RecordingStatus.FAILED.name, errorMessage = "No video saved")
            }
        }
    }
}
