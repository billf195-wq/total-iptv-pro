package com.totaliptv.pro.artwork

import android.content.Context
import java.io.File

/**
 * Optional TMDB key pushed with adb. The settings field wins when it is not blank.
 * The file contents are never logged.
 *
 * adb push my-key.txt /sdcard/Android/data/com.totaliptv.pro/files/tmdb-api-key.txt
 */
object TmdbKeyFile {
    const val FILE_NAME = "tmdb-api-key.txt"
    const val ADB_PATH = "/sdcard/Android/data/com.totaliptv.pro/files/tmdb-api-key.txt"

    fun read(context: Context): String? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return null
        return runCatching {
            file.bufferedReader().use { reader ->
                reader.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            }
        }.getOrNull()
    }
}
