package com.totaliptv.pro.data.update

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveUpdateTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun eachFlavorHasItsOwnDriveManifest() {
        assertEquals(UpdateSources.DRIVE_MANIFEST_TV, UpdateSources.driveManifestId("tv"))
        assertEquals(UpdateSources.DRIVE_MANIFEST_PHONE, UpdateSources.driveManifestId("phone"))
        assertTrue(UpdateSources.DRIVE_MANIFEST_TV != UpdateSources.DRIVE_MANIFEST_PHONE)
        assertEquals("", UpdateSources.driveManifestId("other"))
    }

    @Test
    fun driveManifestParsesAndOldShelfManifestStillParses() {
        val drive = json.decodeFromString(
            UpdateManifest.serializer(),
            """{"versionCode":104,"versionName":"1.4.92","apk":"a.apk","driveFileId":"ABC","url":"","size":123,"sha256":"ff"}"""
        )
        assertEquals(104, drive.versionCode)
        assertEquals(123L, drive.size)
        assertEquals(UpdateSources.driveDownloadUrl("ABC"), UpdateSources.driveApkUrl(drive.url, drive.driveFileId))
        val shelf = json.decodeFromString(UpdateManifest.serializer(), """{"versionCode":103,"versionName":"1.4.91","apk":"TotalIPTVPro.apk"}""")
        assertEquals("", shelf.sha256)
        assertNull(UpdateSources.driveApkUrl(shelf.url, shelf.driveFileId))
        assertEquals("https://x.test/a.apk", UpdateSources.driveApkUrl("https://x.test/a.apk", "ABC"))
    }

    @Test
    fun apkVerification() {
        val bytes = "hello".toByteArray()
        val sha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        assertNull(UpdateSources.verifyApk(bytes, 5, sha))
        assertNull(UpdateSources.verifyApk(bytes, 0, ""))
        assertNotNull(UpdateSources.verifyApk(bytes, 6, ""))
        assertNotNull(UpdateSources.verifyApk(bytes, 5, "00"))
    }

    @Test
    fun updaterNoLongerContactsGitHub() {
        val checker = java.io.File("src/main/java/com/totaliptv/pro/data/update/AppUpdateChecker.kt").readText()
        assertTrue(!checker.lowercase().contains("github"))
        assertTrue(checker.contains("checkDrive()"))
        assertTrue(checker.contains("checkShelf(baseUrl)"))
    }
}
