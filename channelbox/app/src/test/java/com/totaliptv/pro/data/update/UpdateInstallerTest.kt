package com.totaliptv.pro.data.update

import com.totaliptv.pro.ui.settings.PinFocusReturn
import com.totaliptv.pro.ui.settings.PinLockEntry
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 1.4.107: update download/install fixes and PIN lock D-pad focus return. */
class UpdateInstallerTest {
    private val pk = byteArrayOf('P'.code.toByte(), 'K'.code.toByte())
    private val big = 21_038_630L

    @Test
    fun streamedDownloadPassesWhenSizeShaAndZipHeaderMatch() {
        assertNull(UpdateSources.verifyDownload(big, pk, "AbC", big, "abc"))
        assertNull(UpdateSources.verifyDownload(big, pk, "abc", 0, ""))
    }

    @Test
    fun webPageInsteadOfApkIsRejected() {
        val html = "<!".toByteArray()
        assertEquals("The download was not an app file", UpdateSources.verifyDownload(big, html, "x", 0, ""))
        assertNotNull(UpdateSources.verifyDownload(5_000, pk, "x", 0, ""))
        assertNotNull(UpdateSources.verifyDownload(big, byteArrayOf(), "x", 0, ""))
    }

    @Test
    fun sizeOrChecksumMismatchIsRejected() {
        assertNotNull(UpdateSources.verifyDownload(big - 1, pk, "abc", big, "abc"))
        assertEquals("Download checksum mismatch", UpdateSources.verifyDownload(big, pk, "abd", big, "abc"))
    }

    @Test
    fun friendlyErrorsArePlainAndNeverShowAUrl() {
        val offline = UpdateInstaller.friendlyError(UnknownHostException("drive.usercontent.google.com"))
        assertTrue(offline.contains("No internet connection"))
        assertFalse(offline.contains("google.com"))
        assertTrue(UpdateInstaller.friendlyError(SocketTimeoutException("timeout")).contains("timed out"))
        assertTrue(
            UpdateInstaller.friendlyError(IllegalStateException("Download size mismatch (1 vs 2)"))
                .contains("incomplete or blocked")
        )
        val wrapped = UpdateInstaller.friendlyError(IOException("x", UnknownHostException("h")))
        assertTrue(wrapped.contains("No internet connection"))
        val other = UpdateInstaller.friendlyError(IllegalStateException("Download failed (403) https://example.test/a"))
        assertFalse(other.contains("https://"))
        assertTrue(other.startsWith("Download failed: ") && other.endsWith("Choose Install again."))
    }

    @Test
    fun updatedMessageOnlyAfterTheNewVersionIsRunning() {
        assertEquals("Updated to 1.4.107", UpdateInstaller.updatedMessage(119, 119, "1.4.107", 60_000))
        assertNull(UpdateInstaller.updatedMessage(119, 118, "1.4.106", 60_000))
        assertNull(UpdateInstaller.updatedMessage(0, 119, "1.4.107", 60_000))
        assertNull(UpdateInstaller.updatedMessage(119, 119, "1.4.107", 3L * 24 * 60 * 60 * 1000))
    }

    @Test
    fun downloadingLabelShowsPercent() {
        assertEquals("Downloading 1.4.107… 42%", UpdateInstaller.downloadingLabel("1.4.107", 42))
        assertEquals("Downloading 1.4.107…", UpdateInstaller.downloadingLabel("1.4.107", null))
    }

    @Test
    fun pinFocusReturnsToUsedSlotOrLastSlotLeft() {
        // Turn off (slot 3 of 5) leaves only "Set PIN".
        assertEquals(0, PinFocusReturn.slotAfterClose(3, 1))
        assertEquals(2, PinFocusReturn.slotAfterClose(2, 5))
        assertEquals(4, PinFocusReturn.slotAfterClose(4, 5))
        assertEquals(0, PinFocusReturn.slotAfterClose(-1, 5))
        assertEquals(0, PinFocusReturn.slotAfterClose(2, 0))
        assertEquals(10, PinFocusReturn.scrollIndex(12))
        assertEquals(0, PinFocusReturn.scrollIndex(1))
        assertTrue(PinFocusReturn.MAX_SLOTS >= PinLockEntry.entries.size)
    }

    @Test
    fun settingsScreensUseSharedInstallerAndDoNotCallCancelAFailure() {
        listOf(
            "src/tv/java/com/totaliptv/pro/ui/settings/SettingsScreen.kt",
            "src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt",
            "src/phone/java/com/totaliptv/pro/ui/settings/PhoneSettingsScreen.kt"
        ).forEach { path ->
            val src = File(path).readText()
            assertTrue(src.contains("UpdateInstaller.downloadAndInstall("), path)
            assertTrue(src.contains("catch (c: kotlinx.coroutines.CancellationException)"), path)
            assertTrue(src.contains("UpdateInstaller.UNKNOWN_APPS_HINT"), path)
            assertFalse(src.contains("AppUpdateChecker.downloadApk("), path)
        }
        val checker = File("src/main/java/com/totaliptv/pro/data/update/AppUpdateChecker.kt").readText()
        assertFalse(checker.contains("resp.body?.bytes()"), "APK must stream to a file, not a byte array")
        assertTrue(checker.contains("Intent.FLAG_ACTIVITY_CLEAR_TASK"), "installer must not reuse a stale task")
    }

    @Test
    fun pinLockFocusIsWiredForDpadInClassicDesktopAndDialogs() {
        val classic = File("src/tv/java/com/totaliptv/pro/ui/settings/SettingsScreen.kt").readText()
        assertTrue(classic.contains("state = settingsList"))
        assertTrue(classic.contains("focusRequester = pinFocus[index]"))
        assertTrue(classic.contains("settingsList.scrollToItem(PinFocusReturn.scrollIndex(pinSlot0Index))"))
        val desktop = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
        assertTrue(desktop.contains(".focusRequester(pinFocus[slot])"))
        val dialogs = File("src/tv/java/com/totaliptv/pro/ui/settings/PinLockDialogs.kt").readText()
        assertEquals(3, Regex("DialogFirstFocus\\(\\w+\\) \\{").findAll(dialogs).count())
    }
}
