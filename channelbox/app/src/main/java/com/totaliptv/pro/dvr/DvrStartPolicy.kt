package com.totaliptv.pro.dvr

enum class DvrStartReason {
    USER_RECORD,
    SCHEDULE_DUE
}

object DvrStartPolicy {
    const val OPEN_VOD = "open_vod"
    const val PLAY_VOD = "play_vod"
    const val PLAY_ITEM = "play_item"
    const val OPEN_SERIES = "open_series"
    const val PLAY_SERIES = "play_series"
    const val BROWSE = "browse"

    fun allowsImmediateStart(reason: DvrStartReason): Boolean =
        reason == DvrStartReason.USER_RECORD || reason == DvrStartReason.SCHEDULE_DUE

    fun isAutoPlaybackEvent(event: String): Boolean =
        event == OPEN_VOD ||
            event == PLAY_VOD ||
            event == PLAY_ITEM ||
            event == OPEN_SERIES ||
            event == PLAY_SERIES ||
            event == BROWSE
}
