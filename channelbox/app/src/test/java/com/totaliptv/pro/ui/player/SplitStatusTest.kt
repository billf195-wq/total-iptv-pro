package com.totaliptv.pro.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

class SplitStatusTest {
    @Test
    fun bothFailedDoesNotClaimTheOtherKeepsPlaying() {
        assertEquals("This side failed.", SplitStatus.failureMessage(otherSideReady = false))
        assertEquals(
            "This side failed. The other keeps playing.",
            SplitStatus.failureMessage(otherSideReady = true)
        )
    }
}
