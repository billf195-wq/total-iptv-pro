package com.totaliptv.pro2.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("tip2_prefs", Context.MODE_PRIVATE)

    fun load(): SavedPrefs = SavedPrefs(
        sourceType = prefs.getString(KEY_SOURCE, SourceType.XTREAM.name) ?: SourceType.XTREAM.name,
        xtreamBaseUrl = prefs.getString(KEY_BASE, "") ?: "",
        xtreamUsername = prefs.getString(KEY_USER, "") ?: "",
        xtreamPassword = prefs.getString(KEY_PASS, "") ?: "",
        onboarded = prefs.getBoolean(KEY_ONBOARDED, false),
        themeMode = prefs.getString(KEY_THEME, "dark") ?: "dark",
        posterColumns = prefs.getInt(KEY_COLS, 6).coerceIn(5, 6),
        browseSort = prefs.getString(KEY_SORT, "AZ") ?: "AZ",
        updateShelfUrl = prefs.getString(KEY_SHELF, "http://192.168.4.39:8766/")
            ?: "http://192.168.4.39:8766/"
    )

    fun save(p: SavedPrefs) {
        prefs.edit()
            .putString(KEY_SOURCE, p.sourceType)
            .putString(KEY_BASE, p.xtreamBaseUrl)
            .putString(KEY_USER, p.xtreamUsername)
            .putString(KEY_PASS, p.xtreamPassword)
            .putBoolean(KEY_ONBOARDED, p.onboarded)
            .putString(KEY_THEME, p.themeMode)
            .putInt(KEY_COLS, p.posterColumns.coerceIn(5, 6))
            .putString(KEY_SORT, p.browseSort)
            .putString(KEY_SHELF, p.updateShelfUrl)
            .apply()
    }

    fun clearOnboarding() {
        val cur = load()
        save(cur.copy(onboarded = false))
    }

    companion object {
        private const val KEY_SOURCE = "sourceType"
        private const val KEY_BASE = "xtreamBaseUrl"
        private const val KEY_USER = "xtreamUsername"
        private const val KEY_PASS = "xtreamPassword"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_THEME = "themeMode"
        private const val KEY_COLS = "posterColumns"
        private const val KEY_SORT = "browseSort"
        private const val KEY_SHELF = "updateShelfUrl"
    }
}
