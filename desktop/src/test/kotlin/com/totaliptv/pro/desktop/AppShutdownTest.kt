package com.totaliptv.pro.desktop

import com.totaliptv.pro.desktop.data.SeriesEpisode
import com.totaliptv.pro.desktop.input.WindowsTopMost
import com.totaliptv.pro.desktop.ui.ActiveSeriesPlay
import com.totaliptv.pro.desktop.ui.SeriesNextHost
import com.totaliptv.pro.desktop.ui.SplashBranding
import java.util.concurrent.CopyOnWriteArrayList
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
    fun mainComposesOnlyOneApplicationWindow() {
        val main = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/Main.kt")
        assertTrue(main.isFile, "Main.kt should be readable from desktop/ test cwd")
        val text = main.readText()
        assertEquals(
            1,
            Regex("""\bWindow\(""").findAll(text).count(),
            "a second Compose Window keeps the JVM alive after Quit"
        )
        assertFalse(text.contains("SeriesNextOverlay("))
    }

    @Test
    fun overlayIsNotAComposeApplicationWindow() {
        // Two Compose application Windows keep the JVM alive after Quit and
        // the overlay raise path re-shows the main frame (restart loop).
        assertFalse(SeriesNextHost.IS_COMPOSE_APPLICATION_WINDOW)
        val host = SeriesNextHost()
        host.clear()
        assertFalse(host.isOverlayWindowAlive())
    }

    @Test
    fun beginWinsOnceThenFurtherQuitHalts() {
        val exits = AtomicInteger(0)
        val halts = AtomicInteger(0)
        AppShutdown.forceExit = { exits.incrementAndGet() }
        AppShutdown.haltExit = { halts.incrementAndGet() }
        AppShutdown.forceExitDelayMs = 50L
        AppShutdown.haltDelayMs = 5_000L

        assertTrue(AppShutdown.begin())
        assertTrue(AppShutdown.isExiting())
        assertFalse(AppShutdown.begin())

        AppShutdown.requestQuit(
            disposeOverlay = {},
            stopHotkeys = {},
            stopPlayer = {},
            stopTopMost = {},
            exitApplication = {}
        )
        assertEquals(1, halts.get(), "second quit must halt immediately, not relaunch")
        assertEquals(0, exits.get(), "second quit should not wait for System.exit")
    }

    @Test
    fun secondQuitWithinTwoSecondsHaltsEvenIfComposeDidNotExit() {
        val order = CopyOnWriteArrayList<String>()
        val exits = AtomicInteger(0)
        val halts = AtomicInteger(0)
        AppShutdown.forceExit = { exits.incrementAndGet() }
        AppShutdown.haltExit = { halts.incrementAndGet() }
        AppShutdown.forceExitDelayMs = 5_000L
        AppShutdown.haltDelayMs = 5_000L

        AppShutdown.requestQuit(
            disposeOverlay = { order += "overlay" },
            stopHotkeys = { order += "hotkeys" },
            stopPlayer = { order += "player" },
            stopTopMost = { order += "topMost" },
            exitApplication = { order += "compose" }
        )
        AppShutdown.requestQuit(
            disposeOverlay = { order += "overlay2" },
            stopHotkeys = { order += "hotkeys2" },
            stopPlayer = { order += "player2" },
            stopTopMost = { order += "topMost2" },
            exitApplication = { order += "compose2" }
        )

        assertEquals(1, halts.get(), "second quit within 2s force-halts")
        assertEquals(0, exits.get())
        assertFalse(order.contains("compose2"), "halt path must not wait on Compose exitApplication")
    }

    @Test
    fun firstQuitStopsPlayerThenOverlayThenHotkeys() {
        val order = CopyOnWriteArrayList<String>()
        val exits = AtomicInteger(0)
        val halts = AtomicInteger(0)
        AppShutdown.forceExit = { exits.incrementAndGet() }
        AppShutdown.haltExit = { halts.incrementAndGet() }
        AppShutdown.forceExitDelayMs = 30L
        AppShutdown.haltDelayMs = 5_000L

        AppShutdown.requestQuit(
            disposeOverlay = { order += "overlay" },
            stopHotkeys = { order += "hotkeys" },
            stopPlayer = { order += "player" },
            stopTopMost = { order += "topMost" },
            exitApplication = { order += "compose" }
        )

        assertTrue(AppShutdown.isExiting())
        assertEquals(listOf("player", "overlay", "hotkeys", "topMost", "compose"), order.toList())
        Thread.sleep(80)
        assertEquals(1, exits.get(), "JVM force-exit must run once after Compose teardown")
        assertEquals(0, halts.get(), "halt is last resort, not the first exit")
    }

    @Test
    fun overlaySyncWhileExitingDoesNotReviveHostWindow() {
        AppShutdown.begin()
        val host = SeriesNextHost()
        host.sync(
            session = ActiveSeriesPlay(
                episodes = emptyList(),
                seriesName = "Show",
                seriesId = 1,
                current = SeriesEpisode(
                    id = "1-1",
                    title = "E1",
                    season = 1,
                    episodeNum = 1,
                    streamUrl = "http://example.test/1.mp4"
                )
            ),
            darkTheme = true,
            onNext = {},
            onStop = {}
        )
        assertFalse(host.isOverlayWindowAlive())
        val main = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/Main.kt")
        val text = main.readText()
        assertTrue(text.contains("!AppShutdown.isExiting()"))
        assertTrue(text.contains("StreamPlayer.stop()"))
        assertTrue(text.contains("seriesNextHost.disposeOverlay()"))
    }

    @Test
    fun nativeHardExitTriesLibcExitThenKill() {
        val calls = CopyOnWriteArrayList<String>()
        NativeProcessExit.hooks = object : NativeProcessExit.Hooks {
            override fun exitImmediate(status: Int) {
                calls += "exit:$status"
            }
            override fun killSelf() {
                calls += "kill"
            }
        }
        NativeProcessExit.exitNow(0)
        assertEquals(listOf("exit:0", "kill"), calls.toList())
    }

    @Test
    fun nativeHardExitStillKillsWhenExitThrows() {
        val calls = CopyOnWriteArrayList<String>()
        NativeProcessExit.hooks = object : NativeProcessExit.Hooks {
            override fun exitImmediate(status: Int) {
                calls += "exit"
                throw IllegalStateException("libc exit failed")
            }
            override fun killSelf() {
                calls += "kill"
            }
        }
        NativeProcessExit.exitNow(0)
        assertEquals(listOf("exit", "kill"), calls.toList())
    }

    @Test
    fun nonWindowsBackstopUsesLibcNotJvmHalt() {
        assertEquals(!com.totaliptv.pro.desktop.util.AppPaths.isWindows, AppShutdown.usesNativeHardExit)
        if (!AppShutdown.usesNativeHardExit) return
        assertTrue(NativeProcessExit.preload())
        assertTrue(NativeProcessExit.libcReady())
        val text = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/AppShutdown.kt").readText()
        assertTrue(text.contains("NativeProcessExit.exitNow(0)"))
        assertTrue(text.contains("Runtime.getRuntime().halt(0)"))
    }

    @Test
    fun quitFlushesPrefsBeforeShutdown() {
        val text = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/Main.kt").readText()
        val quitFn = text.indexOf("fun quit()")
        val save = text.indexOf("PreferencesStore.save")
        val request = text.indexOf("AppShutdown.requestQuit")
        assertTrue(quitFn >= 0 && save > quitFn && request > save)
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
    fun splashAndWindowTitleShowDesktopVersion() {
        assertEquals("1.2.24", AppVersion.VERSION_NAME)
        assertEquals(36, AppVersion.VERSION_CODE)
        assertEquals("Total IPTV Pro", SplashBranding.APP_TITLE)
        assertEquals("1.2.24", SplashBranding.versionLabel(AppVersion.VERSION_NAME))
        assertEquals("Total IPTV Pro 1.2.24", SplashBranding.windowTitle(AppVersion.VERSION_NAME))
        val main = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/Main.kt")
        assertTrue(main.isFile, "Main.kt should be readable from desktop/ test cwd")
        val text = main.readText()
        assertTrue(
            text.contains("SplashBranding.windowTitle(AppVersion.VERSION_NAME)"),
            "main window title must include the desktop version"
        )
    }
}
