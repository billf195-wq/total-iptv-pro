package com.totaliptv.pro.ui.player

/**
 * Whether the player may lock itself to landscape.
 *
 * Android 8.0 (API 26) throws [IllegalStateException] from [android.app.Activity.onCreate]
 * and from [android.app.Activity.setRequestedOrientation] whenever targetSdk is 26 or
 * higher and the activity uses a fixed orientation. That platform bug was fixed in 8.1.
 * Later releases throw the same exception when a fixed orientation is requested from a
 * window that does not fill the screen (multi-window). The phone player used to declare
 * `sensorLandscape` in the manifest, so the throw happened inside `super.onCreate` and
 * the process died before playback could start. Android TV on API 30 does not hit it.
 */
object PlaybackOrientation {
    const val ANDROID_8_0 = 26

    fun allowLandscapeLock(sdkInt: Int, inMultiWindow: Boolean): Boolean {
        if (sdkInt == ANDROID_8_0) return false
        if (inMultiWindow) return false
        return true
    }
}
