package com.totaliptv.pro.billing

import android.app.Activity
import com.totaliptv.pro.BuildConfig
import com.totaliptv.pro.TotalIptvProApp

/** Stops playback if the trial has ended and the unlock has not been bought. */
fun Activity.finishIfLicenseBlocked(): Boolean {
    val app = application as? TotalIptvProApp ?: return false
    if (!BuildConfig.PLAY_UNLOCK_ENABLED) return false
    return if (!app.license.status.value.allowsUse) {
        finish()
        true
    } else {
        false
    }
}
