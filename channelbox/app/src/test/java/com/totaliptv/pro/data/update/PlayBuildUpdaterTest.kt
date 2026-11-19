package com.totaliptv.pro.data.update

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayBuildUpdaterTest {
    @Test
    fun playManifestRemovesInstallPackagesAndSideloadKeepsIt() {
        val main = File("src/main/AndroidManifest.xml").readText()
        val play = File("src/play/AndroidManifest.xml").readText()
        assertTrue(main.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertFalse(main.contains("tools:node=\"remove\""))
        assertTrue(play.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(play.contains("tools:node=\"remove\""))
        // Player sharing still needs FileProvider on every build, including Play.
        assertTrue(main.contains("androidx.core.content.FileProvider"))
    }

    @Test
    fun updaterUiIsGatedByPlayFlavorFlag() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("dimension = \"distribution\""))
        assertTrue(gradle.contains("SELF_UPDATER_ENABLED\", \"true\""))
        assertTrue(gradle.contains("SELF_UPDATER_ENABLED\", \"false\""))

        val checker = File("src/main/java/com/totaliptv/pro/data/update/AppUpdateChecker.kt").readText()
        assertTrue(checker.contains("SELF_UPDATER_ENABLED"))

        val tv = File("src/tv/java/com/totaliptv/pro/ui/settings/SettingsScreen.kt").readText()
        val phone = File("src/phone/java/com/totaliptv/pro/ui/settings/PhoneSettingsScreen.kt").readText()
        val desktop = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
        for (src in listOf(tv, phone, desktop)) {
            assertTrue(src.contains("BuildConfig.SELF_UPDATER_ENABLED"))
            assertTrue(src.contains("Check for update"))
            assertTrue(src.contains("UpdateInstaller.check(context, updateBaseUrl)"))
        }
        // Catalog reload is not the self-updater and must stay on Play builds,
        // before the Play-gated updater block.
        assertTrue(desktop.contains("Update (reload catalog)"))
        val reloadAt = desktop.indexOf("Update (reload catalog)")
        val gateAt = desktop.indexOf("if (BuildConfig.SELF_UPDATER_ENABLED)")
        assertTrue(reloadAt >= 0 && gateAt > reloadAt)
    }

    @Test
    fun selfUpdaterFlagMatchesDistributionFlavor() {
        val play = com.totaliptv.pro.BuildConfig.FLAVOR.lowercase().contains("play")
        if (play) {
            assertFalse(com.totaliptv.pro.BuildConfig.SELF_UPDATER_ENABLED)
        } else {
            assertTrue(com.totaliptv.pro.BuildConfig.SELF_UPDATER_ENABLED)
        }
    }
}
