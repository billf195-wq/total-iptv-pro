package com.totaliptv.pro.dvr

import android.content.Context
import android.widget.Toast
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.model.MediaItem

object DvrActions {
    fun recorder(context: Context): DvrRecorder =
        (context.applicationContext as TotalIptvProApp).dvr

    fun recordNow(
        context: Context,
        item: MediaItem,
        title: String? = null,
        endMs: Long? = null
    ): Boolean {
        return try {
            recorder(context).startNow(
                channelName = item.name,
                title = title?.takeIf { it.isNotBlank() } ?: item.name,
                streamUrl = item.streamUrl,
                channelId = item.id,
                scheduledEndMs = endMs,
                contentKind = item.kind.name,
                reason = DvrStartReason.USER_RECORD
            )
            val kind = DvrKind.normalize(item.kind.name)
            val verb = if (DvrKind.isFiniteDownload(kind, item.streamUrl)) "Downloading" else "Recording"
            Toast.makeText(context, "$verb ${item.name} on this device", Toast.LENGTH_SHORT).show()
            true
        } catch (t: Throwable) {
            Toast.makeText(context, t.message ?: "Could not record", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun schedule(
        context: Context,
        item: MediaItem,
        title: String,
        startMs: Long,
        endMs: Long
    ): Boolean {
        return try {
            recorder(context).schedule(
                item.name,
                title,
                item.streamUrl,
                startMs,
                endMs,
                item.id,
                item.kind.name
            )
            Toast.makeText(context, "Scheduled $title", Toast.LENGTH_SHORT).show()
            true
        } catch (t: Throwable) {
            Toast.makeText(context, t.message ?: "Could not schedule", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun stop(context: Context) {
        recorder(context).stop()
        Toast.makeText(context, "Stopping recording", Toast.LENGTH_SHORT).show()
    }
}
