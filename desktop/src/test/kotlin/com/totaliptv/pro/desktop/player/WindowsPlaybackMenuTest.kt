package com.totaliptv.pro.desktop.player

import com.sun.jna.platform.win32.WinDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsPlaybackMenuTest {

    @Test
    fun menuListsPauseAudioSubtitlesStopAndNextOnlyForSeries() {
        assertEquals(
            listOf(
                WindowsPlaybackMenu.Action.PAUSE_TOGGLE,
                WindowsPlaybackMenu.Action.AUDIO_TRACK,
                WindowsPlaybackMenu.Action.SUBTITLE_TRACK,
                WindowsPlaybackMenu.Action.STOP
            ),
            WindowsPlaybackMenu.menuActions(hasNextEpisode = false)
        )
        assertEquals(
            WindowsPlaybackMenu.Action.NEXT_EPISODE,
            WindowsPlaybackMenu.menuActions(hasNextEpisode = true).last()
        )
        assertEquals("Pause", WindowsPlaybackMenu.pauseLabel(paused = false))
        assertEquals("Play", WindowsPlaybackMenu.pauseLabel(paused = true))
    }

    @Test
    fun rcCommandsTogglePauseAndCycleTracksWithoutLeavingFullscreen() {
        assertEquals("pause", WindowsPlaybackMenu.rcCommand(WindowsPlaybackMenu.Action.PAUSE_TOGGLE))
        assertEquals("key key-audio-track", WindowsPlaybackMenu.rcCommand(WindowsPlaybackMenu.Action.AUDIO_TRACK))
        assertEquals("key key-subtitle-track", WindowsPlaybackMenu.rcCommand(WindowsPlaybackMenu.Action.SUBTITLE_TRACK))
        assertNull(WindowsPlaybackMenu.rcCommand(WindowsPlaybackMenu.Action.STOP))
        assertNull(WindowsPlaybackMenu.rcCommand(WindowsPlaybackMenu.Action.NEXT_EPISODE))
        assertTrue(VlcControl.pausedFromStatus("( state paused )"))
        assertFalse(VlcControl.pausedFromStatus("( state playing )"))
        assertFalse(VlcControl.pausedFromStatus(null))
    }

    @Test
    fun rightClickOnVlcIsSwallowedAndOtherClicksPassThrough() {
        assertTrue(
            WindowsPlaybackMenu.shouldSwallowRightClick(
                WindowsPlaybackMenu.WM_RBUTTONDOWN,
                targetPid = 40,
                windowPid = 40
            )
        )
        assertTrue(
            WindowsPlaybackMenu.shouldSwallowRightClick(
                WindowsPlaybackMenu.WM_RBUTTONUP,
                targetPid = 40,
                windowPid = 40
            )
        )
        assertFalse(
            WindowsPlaybackMenu.shouldSwallowRightClick(
                WindowsPlaybackMenu.WM_LBUTTONDOWN,
                targetPid = 40,
                windowPid = 40
            )
        )
        assertFalse(
            WindowsPlaybackMenu.shouldSwallowRightClick(
                WindowsPlaybackMenu.WM_RBUTTONUP,
                targetPid = 40,
                windowPid = 41
            )
        )
        assertFalse(WindowsPlaybackMenu.shouldSwallowRightClick(WindowsPlaybackMenu.WM_RBUTTONUP, 0, 0))
        assertTrue(WindowsPlaybackMenu.shouldOpenMenu(WindowsPlaybackMenu.WM_RBUTTONUP))
        assertFalse(WindowsPlaybackMenu.shouldOpenMenu(WindowsPlaybackMenu.WM_RBUTTONDOWN))
        assertTrue(WindowsPlaybackMenu.sameProcess(0x80000000L, WindowsPlaybackMenu.unsignedPid(Int.MIN_VALUE)))
        assertEquals(0xFFFFFFFFL, WindowsPlaybackMenu.unsignedPid(-1))
        assertEquals(WindowsPlaybackMenu.WM_RBUTTONUP, WindowsPlaybackMenu.messageCode(WinDef.WPARAM(0x0205)))
        assertEquals(WindowsPlaybackMenu.WM_RBUTTONDOWN, WindowsPlaybackMenu.messageCode(WinDef.WPARAM(0x0204)))
    }

    @Test
    fun appRootBindsTheMenuAndSinglePlayDoesNotSnapWindows() {
        val root = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/ui/AppRoot.kt").readText()
        assertTrue(root.contains("WindowsPlaybackMenu.bind"))
        assertTrue(root.contains("skipToNextEpisode()"))
        val player = java.io.File("src/main/kotlin/com/totaliptv/pro/desktop/player/StreamPlayer.kt").readText()
        assertTrue(player.contains("WindowsPlaybackMenu.arm"))
        assertTrue(player.contains("useWin32MonitorFullscreen(windows: Boolean, fullscreen: Boolean): Boolean = false"))
        val split = player.substringAfter("internal fun splitSideCommand")
        assertFalse(split.substringBefore("internal fun linuxSplitSideCommand").contains("WindowsPlaybackMenu"))
    }
}
