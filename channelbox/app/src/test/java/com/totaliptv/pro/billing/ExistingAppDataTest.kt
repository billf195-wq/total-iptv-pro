package com.totaliptv.pro.billing

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExistingAppDataTest {
    @Test
    fun emptyDeviceIsNotGrandfathered() {
        assertFalse(
            ExistingAppData.present(
                catalogPrefKeys = emptySet(),
                lastPlaceFile = null,
                watchProgressPrefsExist = false,
                savedLoginPrefsExist = false,
                recordingsNotEmpty = false
            )
        )
    }

    @Test
    fun catalogPreferencesCountAsExistingData() {
        assertTrue(
            ExistingAppData.present(
                catalogPrefKeys = setOf("sources"),
                lastPlaceFile = null,
                watchProgressPrefsExist = false,
                savedLoginPrefsExist = false,
                recordingsNotEmpty = false
            )
        )
    }

    @Test
    fun licenseKeysAloneDoNotCountAsExistingData() {
        assertFalse(
            ExistingAppData.present(
                catalogPrefKeys = LicensePolicy.LICENSE_PREF_KEYS,
                lastPlaceFile = null,
                watchProgressPrefsExist = false,
                savedLoginPrefsExist = false,
                recordingsNotEmpty = false
            )
        )
    }

    @Test
    fun lastPlaceFileCountsWhenItHasContent() {
        val file = File.createTempFile("last-place", ".json")
        file.writeText("""{"screen":"home"}""")
        try {
            assertTrue(
                ExistingAppData.present(
                    catalogPrefKeys = emptySet(),
                    lastPlaceFile = file,
                    watchProgressPrefsExist = false,
                    savedLoginPrefsExist = false,
                    recordingsNotEmpty = false
                )
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun emptyLastPlaceFileDoesNotCount() {
        val file = File.createTempFile("last-place", ".json")
        file.writeText("")
        try {
            assertFalse(
                ExistingAppData.present(
                    catalogPrefKeys = emptySet(),
                    lastPlaceFile = file,
                    watchProgressPrefsExist = false,
                    savedLoginPrefsExist = false,
                    recordingsNotEmpty = false
                )
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun watchProgressSavedLoginOrRecordingsCount() {
        assertTrue(ExistingAppData.present(emptySet(), null, true, false, false))
        assertTrue(ExistingAppData.present(emptySet(), null, false, true, false))
        assertTrue(ExistingAppData.present(emptySet(), null, false, false, true))
    }
}
