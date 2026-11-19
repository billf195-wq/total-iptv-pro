package com.totaliptv.pro.desktop.ui
import kotlin.test.Test
import kotlin.test.assertEquals
class ScrollableChipRowTest {
    @Test fun verticalWheelScrollsSideways() = assertEquals(80f, chipRowWheelDelta(0f, 1f))
    @Test fun wheelUpScrollsLeft() = assertEquals(-80f, chipRowWheelDelta(0f, -1f))
    @Test fun shiftWheelXIsUsed() = assertEquals(160f, chipRowWheelDelta(2f, 0f))
    @Test fun noMovement() = assertEquals(0f, chipRowWheelDelta(0f, 0f))
}
