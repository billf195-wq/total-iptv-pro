package com.totaliptv.pro.ui.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phone play must open [PlayerActivity] and must not depend on split screen.
 * A fixed manifest orientation crashes Android 8.0 inside Activity.onCreate.
 */
class PhonePlaybackEntryTest {

    @Test
    fun android8AndMultiWindowDoNotLockLandscape() {
        assertFalse(PlaybackOrientation.allowLandscapeLock(sdkInt = 26, inMultiWindow = false))
        assertFalse(PlaybackOrientation.allowLandscapeLock(sdkInt = 26, inMultiWindow = true))
        assertFalse(PlaybackOrientation.allowLandscapeLock(sdkInt = 30, inMultiWindow = true))
        assertTrue(PlaybackOrientation.allowLandscapeLock(sdkInt = 24, inMultiWindow = false))
        assertTrue(PlaybackOrientation.allowLandscapeLock(sdkInt = 27, inMultiWindow = false))
        assertTrue(PlaybackOrientation.allowLandscapeLock(sdkInt = 30, inMultiWindow = false))
        assertTrue(PlaybackOrientation.allowLandscapeLock(sdkInt = 35, inMultiWindow = false))
    }

    @Test
    fun phonePlayOpensPlayerActivityNotSplitScreen() {
        val phoneMain = File("src/phone/java/com/totaliptv/pro/MainActivity.kt").readText()
        val phoneBrowse = File("src/phone/java/com/totaliptv/pro/ui/browse/PhoneBrowseScreen.kt").readText()
        val phoneHome = File("src/phone/java/com/totaliptv/pro/ui/home/PhoneHomeScreen.kt").readText()
        val phoneSearch = File("src/phone/java/com/totaliptv/pro/ui/search/PhoneSearchScreen.kt").readText()
        val phoneRecordings = File("src/phone/java/com/totaliptv/pro/ui/dvr/PhoneRecordingsScreen.kt").readText()
        val phoneDetail = File("src/phone/java/com/totaliptv/pro/ui/components/PhoneDetailSheet.kt").readText()
        val phoneSources = listOf(phoneMain, phoneBrowse, phoneHome, phoneSearch, phoneRecordings, phoneDetail)
        phoneSources.forEach { src ->
            assertFalse(src.contains("SplitPlayerActivity"))
            assertFalse(src.contains("GameDayLauncher"))
            assertFalse(src.contains("GameDayPicker"))
        }
        assertTrue(phoneMain.contains("Intent(this, PlayerActivity::class.java)"))
        assertTrue(phoneMain.contains("Couldn't open the player"))
        assertTrue(phoneBrowse.contains("onPlay(item)"))
        assertTrue(phoneDetail.contains("onPlay(enriched)") || phoneDetail.contains("onPlay(ep)"))
    }

    @Test
    fun phoneManifestRemovesSplitPlayerAndFixedOrientation() {
        val phoneManifest = File("src/phone/AndroidManifest.xml").readText()
        assertTrue(phoneManifest.contains("android:name=\".ui.player.PlayerActivity\""))
        assertTrue(phoneManifest.contains("tools:remove=\"android:screenOrientation\""))
        assertFalse(phoneManifest.contains("sensorLandscape"))
        assertTrue(phoneManifest.contains("android:name=\".ui.player.SplitPlayerActivity\""))
        assertTrue(phoneManifest.contains("tools:node=\"remove\""))
        val player = File("src/main/java/com/totaliptv/pro/ui/player/PlayerActivity.kt").readText()
        val onCreate = player.substringAfter("override fun onCreate")
        val lock = onCreate.indexOf("lockLandscapeIfSafe()")
        val superCreate = onCreate.indexOf("super.onCreate")
        assertTrue(superCreate >= 0 && lock > superCreate)
        assertTrue(player.contains("PlaybackOrientation.allowLandscapeLock"))
        assertTrue(player.contains("SCREEN_ORIENTATION_SENSOR_LANDSCAPE"))
        val theme = File("src/main/res/values/themes.xml").readText()
        assertTrue(theme.contains("android:windowIsTranslucent\">false"))
        assertTrue(theme.contains("android:windowIsFloating\">false"))
        val launcher = File("src/main/java/com/totaliptv/pro/ui/player/GameDay.kt").readText()
        assertTrue(launcher.contains("ActivityNotFoundException"))
    }
}
