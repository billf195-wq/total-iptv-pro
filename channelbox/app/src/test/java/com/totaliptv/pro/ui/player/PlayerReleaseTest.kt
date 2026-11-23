package com.totaliptv.pro.ui.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerReleaseTest {
    @Test
    fun releasesOnOrdinaryStop() {
        assertTrue(PlayerRelease.shouldReleaseOnStop(isInPictureInPictureMode = false, isChangingConfigurations = false))
    }

    @Test
    fun keepsPlayerDuringPipAndConfigChange() {
        assertFalse(PlayerRelease.shouldReleaseOnStop(isInPictureInPictureMode = true, isChangingConfigurations = false))
        assertFalse(PlayerRelease.shouldReleaseOnStop(isInPictureInPictureMode = false, isChangingConfigurations = true))
        assertFalse(PlayerRelease.shouldReleaseOnStop(isInPictureInPictureMode = true, isChangingConfigurations = true))
    }
}
