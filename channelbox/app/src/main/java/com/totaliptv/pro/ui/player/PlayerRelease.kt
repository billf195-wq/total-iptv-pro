package com.totaliptv.pro.ui.player

/**
 * When to release ExoPlayer in onStop. Keep it during PIP and configuration
 * changes so picture-in-picture and rotation stay intact.
 */
object PlayerRelease {
    fun shouldReleaseOnStop(
        isInPictureInPictureMode: Boolean,
        isChangingConfigurations: Boolean
    ): Boolean = !isInPictureInPictureMode && !isChangingConfigurations
}
