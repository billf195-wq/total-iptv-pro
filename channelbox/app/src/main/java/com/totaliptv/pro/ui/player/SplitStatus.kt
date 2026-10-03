package com.totaliptv.pro.ui.player

object SplitStatus {
    fun failureMessage(otherSideReady: Boolean): String =
        if (otherSideReady) "This side failed. The other keeps playing."
        else "This side failed."
}
