package com.totaliptv.pro.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressReleaseGateTest {

    @Test
    fun holdThatOpenedTheMenuIsSwallowed() {
        val gate = LongPressReleaseGate()
        // Key repeats of the hold that is still going, then its release.
        assertTrue(gate.onSelectKey(isDown = true, repeatCount = 5))
        assertTrue(gate.onSelectKey(isDown = true, repeatCount = 6))
        assertTrue(gate.onSelectKey(isDown = false, repeatCount = 0))
        assertFalse(gate.allowsClick())
    }

    @Test
    fun freshPressAfterTheMenuOpensWorks() {
        val gate = LongPressReleaseGate()
        assertTrue(gate.onSelectKey(isDown = false, repeatCount = 0)) // old release
        assertFalse(gate.onSelectKey(isDown = true, repeatCount = 0)) // new press passes
        assertTrue(gate.allowsClick())
        assertFalse(gate.onSelectKey(isDown = false, repeatCount = 0)) // its release passes
        assertFalse(gate.onSelectKey(isDown = true, repeatCount = 3)) // later holds pass too
    }

    @Test
    fun releaseWithNoHoldStillNeedsANewPress() {
        val gate = LongPressReleaseGate()
        assertFalse(gate.allowsClick())
        assertTrue(gate.onSelectKey(isDown = false, repeatCount = 0))
        assertFalse(gate.allowsClick())
    }

    @Test
    fun newTouchArmsTheMenu() {
        val gate = LongPressReleaseGate()
        assertFalse(gate.allowsClick())
        gate.onPointerDown()
        assertTrue(gate.allowsClick())
    }

    @Test
    fun initialFocusIsNeverRemove() {
        for (size in 1..5) for (index in 0 until size) for (fav in listOf(true, false)) {
            val actions = ContinueWatchingAction.forPosition(index, size, fav)
            val first = ContinueWatchingAction.initialFocus(actions)
            assertNotEquals(ContinueWatchingAction.REMOVE, first)
            if (index > 0) assertEquals(ContinueWatchingAction.MOVE_FRONT, first)
            else assertEquals(ContinueWatchingAction.MARK_WATCHED, first)
        }
        assertNull(ContinueWatchingAction.initialFocus(listOf(ContinueWatchingAction.REMOVE)))
    }

    @Test
    fun menusUseTheGateAndSafeFocus() {
        val tv = File("src/tv/java/com/totaliptv/pro/ui/home/ContinueWatchingMenu.kt").readText()
        assertTrue(tv.contains(".longPressReleaseGate(gate)"))
        assertTrue(tv.contains("initialFocus(actions)"))
        assertFalse(tv.contains("index == 0) Modifier.focusRequester"))
        assertEquals(2, Regex("gate\\.allowsClick\\(\\)").findAll(tv).count())
        val phone = File("src/phone/java/com/totaliptv/pro/ui/home/PhoneContinueWatchingMenu.kt").readText()
        assertTrue(phone.contains("longPressReleaseGate(gate)"))
        assertEquals(2, Regex("gate\\.allowsClick\\(\\)").findAll(phone).count())
        // Desktop mode opens the same TV dialog.
        val desktop = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopAppRoot.kt").readText()
        assertTrue(desktop.contains("ContinueWatchingMenuDialog("))
    }

    @Test
    fun liveChannelMenuUsesTheGateAndDoesNotFocusAButtonOnOpen() {
        val desktop = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopComponents.kt").readText()
        assertTrue(desktop.contains("fun DesktopActionDialog("))
        assertTrue(desktop.contains(".longPressReleaseGate(gate)"))
        assertTrue(desktop.contains("gate.allowsClick()"))
        assertTrue(desktop.contains("titleFocus.requestFocus()"))
        assertFalse(desktop.contains("first.requestFocus()"))
        assertTrue(desktop.contains("val titleFocus = remember"))
    }
}
