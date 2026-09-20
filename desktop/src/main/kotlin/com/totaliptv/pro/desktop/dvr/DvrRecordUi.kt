package com.totaliptv.pro.desktop.dvr

/**
 * Idle vs active Record control. The button for the item being captured
 * stays lit; every other Record control stays idle even if something else
 * is recording.
 */
object DvrRecordUi {
    const val IDLE_LABEL = "Record"
    const val ACTIVE_LABEL = "Recording…"
    const val IDLE_EPISODE_LABEL = "Record episode"
    const val IDLE_MOVIE_LABEL = "Record movie"

    data class Appearance(
        val activeForThisItem: Boolean,
        val label: String,
        val selected: Boolean
    )

    fun matches(
        activeStreamUrl: String?,
        activeChannelId: String?,
        itemId: String?,
        itemStreamUrl: String?
    ): Boolean {
        val url = itemStreamUrl?.trim().orEmpty()
        val activeUrl = activeStreamUrl?.trim().orEmpty()
        if (url.isNotBlank() && activeUrl.isNotBlank() && urlsEqual(url, activeUrl)) return true
        val id = itemId?.trim().orEmpty()
        val activeId = activeChannelId?.trim().orEmpty()
        return id.isNotBlank() && id == activeId
    }

    fun matches(active: RecordingEntry?, itemId: String?, itemStreamUrl: String?): Boolean {
        if (active == null || !active.isActive()) return false
        return matches(active.streamUrl, active.channelId, itemId, itemStreamUrl)
    }

    fun appearance(
        active: RecordingEntry?,
        itemId: String?,
        itemStreamUrl: String?,
        idleLabel: String = IDLE_LABEL
    ): Appearance {
        val match = matches(active, itemId, itemStreamUrl)
        return Appearance(
            activeForThisItem = match,
            label = if (match) ACTIVE_LABEL else idleLabel,
            selected = match
        )
    }

    fun idleLabelForKind(kind: String?): String = when (DvrKind.normalize(kind)) {
        DvrKind.SERIES -> IDLE_EPISODE_LABEL
        DvrKind.VOD -> IDLE_MOVIE_LABEL
        else -> IDLE_LABEL
    }

    fun urlsEqual(a: String, b: String): Boolean {
        if (a == b) return true
        return a.substringBefore('?') == b.substringBefore('?')
    }
}
