package com.totaliptv.pro.desktop

import com.totaliptv.pro.desktop.input.WindowsTopMost
import com.totaliptv.pro.desktop.ui.SplashBranding
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppShutdownTest {
    @AfterTest
    fun reset() {
        AppShutdown.resetForTests()
    }

    @Test
    fun beginWinsOnceThenFurtherQuitHardExits() {
        val exits = AtomicInteger(0)
        AppShutdown.forceExit = { exits.incrementAndGet() }
        AppShutdown.forceExitDelayMs = 50L

        assertTrue(AppShutdown.begin())
        assertTrue(AppShutdown.isExiting())
        assertFalse(AppShutdown.begin())

        AppShutdown.requestQuit(
            clearOverlay = {},
            stopPlayer = {},
            stopHotkeys = {},
            stopTopMost = {},
            exitApplication = {}
        )
        assertEquals(1, exits.get(), "second quit must force-exit immediately, not relaunch")
    }

    @Test
    fun firstQuitRunsTeardownThenSchedulesOneForceExit() {
        val exits = AtomicInteger(0)
        val overlayCleared = AtomicInteger(0)
        val playerStopped = AtomicInteger(0)
        val hotkeysStopped = AtomicInteger(0)
        val topMostStopped = AtomicInteger(0)
        val appExited = AtomicInteger(0)
        AppShutdown.forceExit = { exits.incrementAndGet() }
        AppShutdown.forceExitDelayMs = 30L

        AppShutdown.requestQuit(
            clearOverlay = { overlayCleared.incrementAndGet() },
            stopPlayer = { playerStopped.incrementAndGet() },
            stopHotkeys = { hotkeysStopped.incrementAndGet() },
            stopTopMost = { topMostStopped.incrementAndGet() },
            exitApplication = { appExited.incrementAndGet() }
        )

        assertTrue(AppShutdown.isExiting())
        assertEquals(1, overlayCleared.get())
        assertEquals(1, playerStopped.get())
        assertEquals(1, hotkeysStopped.get())
        assertEquals(1, topMostStopped.get())
        assertEquals(1, appExited.get())
        Thread.sleep(80)
        assertEquals(1, exits.get(), "JVM force-exit must run once after Compose teardown")
    }

    @Test
    fun ctrlQIsTheQuitKey() {
        assertTrue(AppShutdown.isQuitCombo(keyDown = true, ctrlOrMeta = true, isQ = true))
        assertFalse(AppShutdown.isQuitCombo(keyDown = false, ctrlOrMeta = true, isQ = true))
        assertFalse(AppShutdown.isQuitCombo(keyDown = true, ctrlOrMeta = false, isQ = true))
        assertFalse(AppShutdown.isQuitCombo(keyDown = true, ctrlOrMeta = true, isQ = false))
    }

    @Test
    fun overlayRaiseMustNotReshowWindowsOrBlockOnVlc() {
        assertFalse(WindowsTopMost.raiseFlagsIncludeShowWindow())
        assertEquals(0, WindowsTopMost.RAISE_FLAGS and WindowsTopMost.SWP_SHOWWINDOW)
        assertTrue((WindowsTopMost.RAISE_FLAGS and WindowsTopMost.SWP_NOACTIVATE) != 0)
        assertTrue((WindowsTopMost.RAISE_FLAGS and WindowsTopMost.SWP_ASYNCWINDOWPOS) != 0)
        assertTrue((WindowsTopMost.RAISE_FLAGS and WindowsTopMost.SWP_NOOWNERZORDER) != 0)
    }

    @Test
    fun splashAppendsDesktopVersionInSmallerSuffix() {
        assertEquals("1.2.6", AppVersion.VERSION_NAME)
        assertEquals(18, AppVersion.VERSION_CODE)
        assertEquals("Total IPTV Pro", SplashBranding.APP_TITLE)
        assertEquals("1.2.6", SplashBranding.versionLabel(AppVersion.VERSION_NAME))
    }
}
