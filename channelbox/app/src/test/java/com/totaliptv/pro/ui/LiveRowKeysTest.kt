package com.totaliptv.pro.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveRowKeysTest {
    @Test
    fun longPressOnKeyDownRecordsAndTheMatchingKeyUpIsConsumed() {
        val first = LiveRowKeys.decide(
            isOk = true,
            isMenu = false,
            keyDown = true,
            repeatCount = 0,
            isLongPress = false,
            longAlreadyFired = false
        )
        assertEquals(LiveRowKeys.Action.IGNORE, first)
        val held = LiveRowKeys.decide(
            isOk = true,
            isMenu = false,
            keyDown = true,
            repeatCount = 1,
            isLongPress = true,
            longAlreadyFired = false
        )
        assertEquals(LiveRowKeys.Action.RECORD, held)
        val again = LiveRowKeys.decide(
            isOk = true,
            isMenu = false,
            keyDown = true,
            repeatCount = 2,
            isLongPress = true,
            longAlreadyFired = true
        )
        assertEquals(LiveRowKeys.Action.CONSUME, again)
        val up = LiveRowKeys.decide(
            isOk = true,
            isMenu = false,
            keyDown = false,
            repeatCount = 0,
            isLongPress = false,
            longAlreadyFired = true
        )
        assertEquals(LiveRowKeys.Action.CONSUME, up)
    }

    @Test
    fun menuOpensRecordOnKeyDownAndConsumesKeyUp() {
        assertEquals(
            LiveRowKeys.Action.RECORD,
            LiveRowKeys.decide(false, isMenu = true, keyDown = true, 0, false, false)
        )
        assertEquals(
            LiveRowKeys.Action.CONSUME,
            LiveRowKeys.decide(false, isMenu = true, keyDown = false, 0, false, false)
        )
    }

    @Test
    fun shortOkIsLeftForTheClickHandler() {
        assertEquals(
            LiveRowKeys.Action.IGNORE,
            LiveRowKeys.decide(true, false, keyDown = false, 0, false, longAlreadyFired = false)
        )
    }
}
