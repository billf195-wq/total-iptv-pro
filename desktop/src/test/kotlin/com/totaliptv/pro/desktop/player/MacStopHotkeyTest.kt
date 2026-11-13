package com.totaliptv.pro.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals

class MacStopHotkeyTest {
    @Test
    fun escapeUsesTheMacVirtualKeyCode() {
        assertEquals(0x35, MacStopHotkey.KEY_ESCAPE)
        assertEquals(0x54495052, MacStopHotkey.HOTKEY_SIGNATURE)
        assertEquals(1, MacStopHotkey.HOTKEY_ID)
    }
}
