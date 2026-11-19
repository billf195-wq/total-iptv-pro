package com.totaliptv.pro.billing

import android.content.Context
import java.io.File

/**
 * "Existing app data" used to grandfather users who already had Total IPTV Pro
 * on this device before the paid update. Looks at catalog preferences and
 * other on-device files, never at a Google account.
 */
object ExistingAppData {
    fun present(
        catalogPrefKeys: Set<String>,
        lastPlaceFile: File?,
        watchProgressPrefsExist: Boolean,
        savedLoginPrefsExist: Boolean,
        recordingsNotEmpty: Boolean
    ): Boolean {
        if (catalogPrefKeys.any { it !in LicensePolicy.LICENSE_PREF_KEYS }) return true
        if (lastPlaceFile != null && lastPlaceFile.isFile && lastPlaceFile.length() > 0L) return true
        if (watchProgressPrefsExist) return true
        if (savedLoginPrefsExist) return true
        if (recordingsNotEmpty) return true
        return false
    }

    fun presentOnDevice(context: Context, catalogPrefKeys: Set<String>): Boolean {
        val app = context.applicationContext
        val lastPlace = File(app.filesDir, "last-place.json")
        val watch = app.getSharedPreferences("watch_progress", Context.MODE_PRIVATE)
        val login = app.getSharedPreferences("saved_login", Context.MODE_PRIVATE)
        val watchExists = !watch.getString("entries", null).isNullOrBlank()
        val loginExists = !login.getString("login", null).isNullOrBlank()
        val recordings = defaultRecordingsDir(app).let { dir ->
            dir.isDirectory && dir.walkTopDown().any { it.isFile }
        }
        return present(catalogPrefKeys, lastPlace, watchExists, loginExists, recordings)
    }

    private fun defaultRecordingsDir(context: Context): File {
        val movies = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
        return File(movies ?: context.filesDir, "TotalIptvPro/Recordings")
    }
}
