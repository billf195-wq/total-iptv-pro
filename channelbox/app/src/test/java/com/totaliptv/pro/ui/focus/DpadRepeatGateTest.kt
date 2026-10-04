package com.totaliptv.pro.ui.focus

import android.view.KeyEvent
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpadRepeatGateTest {
    private val down = KeyEvent.KEYCODE_DPAD_DOWN
    private val a = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP

    @Test fun firstPressAndKeyUpAlwaysPass() {
        val g = DpadRepeatGate()
        assertFalse(g.shouldDrop(down, a, 0, 1000, 5000))
        assertFalse(g.shouldDrop(down, up, 0, 1000, 5000))
    }

    @Test fun staleRepeatIsDropped() {
        val g = DpadRepeatGate()
        assertTrue(g.shouldDrop(down, a, 3, 1000, 1000 + DpadRepeatGate.STALE_MS + 1))
    }

    @Test fun repeatsAreSpacedOut() {
        val g = DpadRepeatGate()
        g.onDispatched(down, 1, 1000, 5)
        assertTrue(g.shouldDrop(down, a, 2, 1030, 1030))
        assertFalse(g.shouldDrop(down, a, 3, 1080, 1080))
    }

    @Test fun slowRepeatBlocksUntilRelease() {
        val g = DpadRepeatGate()
        g.onDispatched(down, 2, 1000, DpadRepeatGate.SLOW_DISPATCH_MS)
        assertTrue(g.shouldDrop(down, a, 3, 2000, 2000))
        assertFalse(g.shouldDrop(KeyEvent.KEYCODE_DPAD_UP, a, 0, 2000, 2000))
        assertFalse(g.shouldDrop(down, up, 0, 2100, 2100))
        assertFalse(g.shouldDrop(down, a, 0, 2200, 2200))
    }

    @Test fun nonDpadKeysUntouched() {
        val g = DpadRepeatGate()
        g.onDispatched(down, 2, 1000, 999)
        assertFalse(g.shouldDrop(KeyEvent.KEYCODE_DPAD_CENTER, a, 5, 0, 99999))
        assertFalse(g.shouldDrop(KeyEvent.KEYCODE_BACK, a, 5, 0, 99999))
    }

    @Test fun manifestDisablesBackup() {
        val m = sequenceOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File).first { it.isFile }.readText()
        assertTrue(m.contains("android:allowBackup=\"false\""))
        assertTrue(m.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }
}