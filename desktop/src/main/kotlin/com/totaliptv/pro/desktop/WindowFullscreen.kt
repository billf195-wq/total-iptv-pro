package com.totaliptv.pro.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 1.2.28 "Start in full screen": shared state between the main window (Main.kt)
 * and Settings, plus the pure key rules so they can be unit tested.
 */
object WindowFullscreen {
    /** Settings sets this to ask the main window to enter (true) or leave (false) full screen now. */
    var request by mutableStateOf<Boolean?>(null)

    enum class EscAction { DISMISS_BANNER, STOP_SPLIT, STOP_PLAYBACK, PASS_TO_PLAYER, LEAVE_FULLSCREEN, NONE }

    /**
     * What Esc does in the app window. Order matches 1.2.27 so nothing changes
     * during playback: the Next banner and Game Day split keep their old jobs,
     * and while a video plays Esc does NOT drop the app out of full screen.
 * On macOS and Linux, [stopPlaybackOnEsc] makes Esc stop the player instead of only
 * leaving VLC fullscreen (which looked like the picture shrinking).
     */
    fun escAction(
        fullscreen: Boolean,
        bannerShowing: Boolean,
        splitActive: Boolean,
        playing: Boolean,
        stopPlaybackOnEsc: Boolean = false
    ): EscAction = when {
        bannerShowing -> EscAction.DISMISS_BANNER
        splitActive -> EscAction.STOP_SPLIT
        playing && stopPlaybackOnEsc -> EscAction.STOP_PLAYBACK
        playing -> EscAction.PASS_TO_PLAYER
        fullscreen -> EscAction.LEAVE_FULLSCREEN
        else -> EscAction.NONE
    }

    /** Small log next to prefs (window-debug.log, capped) so a full-screen problem on a TV PC can be traced. */
    fun log(msg: String) {
        runCatching {
            val f = com.totaliptv.pro.desktop.util.AppPaths.configDir.resolve("window-debug.log").toFile()
            if (f.length() > 200_000) f.delete()
            f.appendText("${java.time.LocalDateTime.now()} $msg\n")
        }
    }

    /** F11: flip and remember (the saved value is what the next start uses). */
    fun toggled(fullscreen: Boolean): Boolean = !fullscreen
}
