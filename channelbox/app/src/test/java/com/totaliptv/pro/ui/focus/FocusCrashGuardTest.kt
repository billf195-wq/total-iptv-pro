package com.totaliptv.pro.ui.focus

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusCrashGuardTest {
    @Test
    fun uninitializedFocusRequesterIsRecognized() {
        val boom = IllegalStateException(
            "FocusRequester is not initialized. Here are some possible fixes:"
        )
        assertTrue(SafeFocus.isUninitialized(boom))
        assertTrue(SafeFocus.isUninitialized(RuntimeException("wrap", boom)))
        assertFalse(SafeFocus.isUninitialized(IllegalStateException("other")))
        assertFalse(SafeFocus.isUninitialized(IllegalArgumentException("FocusRequester is not initialized")))
    }

    @Test
    fun directionalTargetsAreNotStaticAndActivitiesSwallowTheCrash() {
        val field = File("src/main/java/com/totaliptv/pro/ui/components/DpadSearchField.kt").readText()
        assertFalse(field.contains("down = downFocus"))
        val filter = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
            .substringAfter("fun FilterBar")
        assertFalse(filter.contains("up = searchFocus"))
        assertFalse(filter.contains("down = belowFocus"))
        val panes = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
        assertFalse(panes.contains("down = firstRowFocus"))
        val gameDay = File("src/main/java/com/totaliptv/pro/ui/player/GameDay.kt").readText()
        assertFalse(gameDay.contains("down = startFocus"))
        assertFalse(gameDay.contains("up = leftFocus"))
        for (path in listOf(
            "src/tv/java/com/totaliptv/pro/MainActivity.kt",
            "src/phone/java/com/totaliptv/pro/MainActivity.kt",
            "src/main/java/com/totaliptv/pro/ui/player/PlayerActivity.kt",
            "src/main/java/com/totaliptv/pro/ui/player/SplitPlayerActivity.kt"
        )) {
            assertTrue(path, File(path).readText().contains("FocusCrashGuard.guard"))
        }
    }
}
