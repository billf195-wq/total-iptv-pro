package com.totaliptv.pro.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateSourcesTest {
    @Test
    fun migratesOldShelfHostAndKeepsPhonePath() {
        assertEquals(
            "http://192.168.4.33:8765/",
            UpdateSources.migrateShelfHost("http://192.168.4.37:8765/")
        )
        assertEquals(
            "http://192.168.4.33:8765/phone/",
            UpdateSources.migrateShelfHost("http://192.168.4.37:8765/phone/")
        )
        assertEquals(
            "http://192.168.4.33:8765/",
            UpdateSources.migrateShelfHost("http://192.168.4.33:8765/")
        )
        assertEquals(
            "http://10.0.0.8:8765/",
            UpdateSources.migrateShelfHost("http://10.0.0.8:8765/")
        )
    }

    @Test
    fun phoneVersionNameMatchesAssetWithoutPhoneSuffix() {
        assertEquals(0, UpdateSources.compareVersions("1.4.36-phone", "1.4.36"))
        assertTrue(UpdateSources.compareVersions("1.4.37", "1.4.36-phone") > 0)
        assertTrue(UpdateSources.compareVersions("1.4.60", "1.4.59") > 0)
        assertTrue(UpdateSources.compareVersions("1.4.9", "1.4.10") < 0)
    }

    @Test
    fun listsReleasesAndSkipsDesktopLatestWithoutAndroidApk() {
        val desktopLatest = UpdateSources.ReleaseListing(
            assets = listOf(
                UpdateSources.Asset(
                    "TotalIptvPro-Desktop-1.2.22-linux.tar.gz",
                    "https://github.com/billf195-wq/total-iptv-pro/releases/download/v1.2.22/TotalIptvPro-Desktop-1.2.22-linux.tar.gz"
                )
            )
        )
        val tvOlder = UpdateSources.ReleaseListing(
            assets = listOf(
                UpdateSources.Asset(
                    "TotalIPTVPro-android-tv-1.4.59-debug.apk",
                    "https://github.com/billf195-wq/total-iptv-pro/releases/download/v1.4.59/TotalIPTVPro-android-tv-1.4.59-debug.apk"
                ),
                UpdateSources.Asset(
                    "TotalIPTVPro-android-phone-1.4.36-debug.apk",
                    "https://github.com/billf195-wq/total-iptv-pro/releases/download/v1.4.59/TotalIPTVPro-android-phone-1.4.36-debug.apk"
                )
            )
        )
        val tvNewer = UpdateSources.ReleaseListing(
            assets = listOf(
                UpdateSources.Asset(
                    "TotalIPTVPro-android-tv-1.4.60-debug.apk",
                    "https://example.com/TotalIPTVPro-android-tv-1.4.60-debug.apk"
                )
            )
        )
        val releases = listOf(desktopLatest, tvNewer, tvOlder)
        val tv = UpdateSources.pickNewerApk(releases, "tv", "1.4.59")
        assertEquals("1.4.60", tv?.versionName)
        assertTrue(tv!!.url.contains("1.4.60"))
        assertNull(UpdateSources.pickNewerApk(releases, "tv", "1.4.60"))

        val phone = UpdateSources.pickNewerApk(releases, "phone", "1.4.36-phone")
        assertNull(phone)
        val phoneNext = UpdateSources.ReleaseListing(
            assets = listOf(
                UpdateSources.Asset(
                    "TotalIPTVPro-android-phone-1.4.37-debug.apk",
                    "https://example.com/TotalIPTVPro-android-phone-1.4.37-debug.apk"
                )
            )
        )
        val pickedPhone = UpdateSources.pickNewerApk(listOf(desktopLatest, phoneNext), "phone", "1.4.36-phone")
        assertEquals("1.4.37", pickedPhone?.versionName)
    }

    @Test
    fun ignoresDraftsAndReadsNextLink() {
        val draft = UpdateSources.ReleaseListing(
            draft = true,
            assets = listOf(
                UpdateSources.Asset(
                    "TotalIPTVPro-android-tv-9.0.0-debug.apk",
                    "https://example.com/draft.apk"
                )
            )
        )
        assertNull(UpdateSources.pickNewerApk(listOf(draft), "tv", "1.4.59"))
        val header = "<https://api.github.com/repositories/1/releases?page=2>; rel=\"next\", <https://api.github.com/repositories/1/releases?page=3>; rel=\"last\""
        assertEquals(
            "https://api.github.com/repositories/1/releases?page=2",
            UpdateSources.nextPageUrl(header)
        )
        assertNull(UpdateSources.nextPageUrl(null))
    }
}
