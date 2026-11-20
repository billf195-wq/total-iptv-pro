package com.totaliptv.pro.billing

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicenseUiSourceTest {
    @Test
    fun settingsScreensShowDaysLeftAndBuy() {
        val tv = File("src/tv/java/com/totaliptv/pro/ui/settings/SettingsScreen.kt").readText()
        val phone = File("src/phone/java/com/totaliptv/pro/ui/settings/PhoneSettingsScreen.kt").readText()
        val desktop = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
        for (src in listOf(tv, phone, desktop)) {
            assertTrue(src.contains("LicenseStrings.statusLine"))
            assertTrue(src.contains("LicenseStrings.buyLabel"))
            assertTrue(src.contains("LicenseStrings.RESTORE"))
            assertTrue(src.contains("license.buy") || src.contains("app.license.buy") || src.contains("app?.license?.buy"))
        }
    }

    @Test
    fun expiredBuyScreensExistOnTvAndPhone() {
        val tv = File("src/tv/java/com/totaliptv/pro/ui/billing/TvBuyScreen.kt").readText()
        val phone = File("src/phone/java/com/totaliptv/pro/ui/billing/PhoneBuyScreen.kt").readText()
        val tvMain = File("src/tv/java/com/totaliptv/pro/MainActivity.kt").readText()
        val phoneMain = File("src/phone/java/com/totaliptv/pro/MainActivity.kt").readText()
        assertTrue(tv.contains("LicenseStrings.BUY_SCREEN_TITLE"))
        assertTrue(phone.contains("LicenseStrings.BUY_SCREEN_TITLE"))
        assertTrue(tvMain.contains("TvBuyScreen"))
        assertTrue(tvMain.contains("!licenseStatus.allowsUse"))
        assertTrue(phoneMain.contains("PhoneBuyScreen"))
        assertTrue(phoneMain.contains("!licenseStatus.allowsUse"))
        assertTrue(tvMain.contains("refreshInBackground"))
        assertTrue(phoneMain.contains("refreshInBackground"))
    }

    @Test
    fun playersRefuseExpiredSessions() {
        val player = File("src/main/java/com/totaliptv/pro/ui/player/PlayerActivity.kt").readText()
        val split = File("src/main/java/com/totaliptv/pro/ui/player/SplitPlayerActivity.kt").readText()
        assertTrue(player.contains("finishIfLicenseBlocked()"))
        assertTrue(split.contains("finishIfLicenseBlocked()"))
    }

    @Test
    fun billingLibraryIsPermissiveAndProductIdIsWired() {
        val toml = File("../gradle/libs.versions.toml").readText()
        val gradle = File("build.gradle.kts").readText()
        val gateway = File("src/main/java/com/totaliptv/pro/billing/PlayBillingGateway.kt").readText()
        val app = File("src/main/java/com/totaliptv/pro/TotalIptvProApp.kt").readText()
        assertTrue(toml.contains("billing = \"8.3.0\""))
        assertTrue(gradle.contains("libs.billing"))
        assertTrue(gradle.contains("Apache-2.0"))
        assertTrue(gateway.contains("LicensePolicy.PRODUCT_ID"))
        assertTrue(gateway.contains("queryPurchasesAsync"))
        assertTrue(gateway.contains("acknowledgePurchase"))
        assertTrue(app.contains("license.start()"))
        val store = File("src/main/java/com/totaliptv/pro/billing/LicenseStore.kt").readText()
        assertTrue(store.contains("tip_license"))
        assertFalse(store.contains("prefs.clear()"))
    }

    @Test
    fun versionsWereBumpedForThePlayUnlock() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("versionName = \"1.4.109\""))
        assertTrue(gradle.contains("versionName = \"1.4.86-phone\""))
        assertTrue(gradle.contains("versionCode = 121"))
        assertTrue(gradle.contains("versionCode = 98"))
        assertFalse(gradle.contains("versionName = \"1.4.102\""))
        assertFalse(gradle.contains("versionName = \"1.4.79-phone\""))
    }
}
