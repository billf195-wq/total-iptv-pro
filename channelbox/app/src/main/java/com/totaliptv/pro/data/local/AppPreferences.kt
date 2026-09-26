package com.totaliptv.pro.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.totaliptv.pro.BuildConfig
import com.totaliptv.pro.data.update.UpdateSources
import com.totaliptv.pro.data.model.FavoriteRef
import com.totaliptv.pro.ui.theme.AccentPreset
import com.totaliptv.pro.ui.theme.AppearanceMode
import com.totaliptv.pro.data.model.PlaylistSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "total_iptv_pro")

/** Preferred external/built-in player for streams. */
enum class PreferredPlayer(val storageValue: String, val label: String) {
    BUILTIN("builtin", "Built-in"),
    ASK("ask", "Ask / error fallback"),
    VLC("vlc", "VLC");

    companion object {
        fun fromStorage(raw: String?): PreferredPlayer =
            entries.firstOrNull { it.storageValue == raw } ?: BUILTIN

        fun next(current: PreferredPlayer): PreferredPlayer {
            val all = entries
            return all[(all.indexOf(current) + 1) % all.size]
        }
    }
}


/** Classic leanback TV UI vs Desktop (Pro 2 / Ubuntu amber sidebar) layout. */
enum class AppLayoutMode(val storageValue: String, val label: String) {
    CLASSIC("classic", "Classic"),
    DESKTOP("desktop", "Desktop");

    companion object {
        fun fromStorage(raw: String?): AppLayoutMode =
            entries.firstOrNull { it.storageValue == raw } ?: CLASSIC

        fun next(current: AppLayoutMode): AppLayoutMode =
            if (current == CLASSIC) DESKTOP else CLASSIC
    }
}

class AppPreferences(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val sourcesKey = stringPreferencesKey("sources")
    private val favoritesKey = stringPreferencesKey("favorites")
    private val activeSourceKey = stringPreferencesKey("active_source_id")
    private val preferredPlayerKey = stringPreferencesKey("preferred_player")
    private val updateBaseUrlKey = stringPreferencesKey("update_base_url")
    private val appearanceKey = stringPreferencesKey("appearance_mode")
    private val accentKey = stringPreferencesKey("accent_preset")
    private val appLayoutKey = stringPreferencesKey("app_layout_mode")
    private val posterColumnsKey = intPreferencesKey("poster_columns")
    private val recordingsDirKey = stringPreferencesKey("recordings_dir")
    private val autoPipKey = booleanPreferencesKey("auto_pip")
    private val sharpPostersKey = booleanPreferencesKey("sharp_posters")
    private val tmdbRatingsKey = booleanPreferencesKey("tmdb_ratings")
    private val tmdbApiKeyKey = stringPreferencesKey("tmdb_api_key")

    companion object {
        /** Flavor-specific: TV root shelf vs phone /phone/ channel. */
        val DEFAULT_UPDATE_BASE_URL: String
            get() = BuildConfig.DEFAULT_UPDATE_BASE_URL

        val POSTER_COLUMN_OPTIONS: List<Int> = listOf(5, 6, 8, 11)

        /** Home-button PiP is on for the phone app and off for Android TV until the user turns it on. */
        fun defaultAutoPip(): Boolean = !BuildConfig.FLAVOR.equals("tv", ignoreCase = true)

        fun normalizePosterColumns(raw: Int?): Int =
            when (raw) {
                5, 6, 8, 11 -> raw
                else -> 6
            }
    }

    val sources: Flow<List<PlaylistSource>> = context.dataStore.data.map { prefs ->
        prefs[sourcesKey]?.let { decodeList(it) } ?: emptyList()
    }

    val favorites: Flow<List<FavoriteRef>> = context.dataStore.data.map { prefs ->
        prefs[favoritesKey]?.let { decodeFavs(it) } ?: emptyList()
    }

    val activeSourceId: Flow<String?> = context.dataStore.data.map { it[activeSourceKey] }

    val preferredPlayer: Flow<PreferredPlayer> = context.dataStore.data.map { prefs ->
        PreferredPlayer.fromStorage(prefs[preferredPlayerKey])
    }

    val updateBaseUrl: Flow<String> = context.dataStore.data.map { prefs ->
        val raw = prefs[updateBaseUrlKey]?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_UPDATE_BASE_URL
        UpdateSources.migrateShelfHost(raw)
    }

    val appearanceMode: Flow<AppearanceMode> = context.dataStore.data.map { prefs ->
        AppearanceMode.fromStorage(prefs[appearanceKey])
    }

    val accentPreset: Flow<AccentPreset> = context.dataStore.data.map { prefs ->
        AccentPreset.fromStorage(prefs[accentKey])
    }

    val appLayoutMode: Flow<AppLayoutMode> = context.dataStore.data.map { prefs ->
        AppLayoutMode.fromStorage(prefs[appLayoutKey])
    }

    /** Posters/banners per row on classic + desktop browse grids. Allowed: 5, 6, 8, 11. */
    val posterColumns: Flow<Int> = context.dataStore.data.map { prefs ->
        normalizePosterColumns(prefs[posterColumnsKey])
    }

    /** Optional recordings folder on this device. Blank = app Movies/TotalIptvPro/Recordings. */
    val recordingsDir: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[recordingsDirKey]?.trim().orEmpty()
    }

    val autoPip: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[autoPipKey] ?: defaultAutoPip()
    }

    /** HD artwork. Default on. TV settings only; phone does not read this. */
    val sharpPosters: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[sharpPostersKey] ?: true
    }

    /** TMDB vote averages. Default on. Honored only when a usable key is present. */
    val tmdbRatings: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[tmdbRatingsKey] ?: true
    }

    /** Optional v3 32-hex key or v4 read token. Blank falls back to tmdb-api-key.txt. */
    val tmdbApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[tmdbApiKeyKey].orEmpty()
    }

    suspend fun getSources(): List<PlaylistSource> = sources.first()

    suspend fun getFavorites(): List<FavoriteRef> = favorites.first()

    suspend fun getActiveSourceId(): String? = activeSourceId.first()

    suspend fun getPreferredPlayer(): PreferredPlayer =
        PreferredPlayer.fromStorage(context.dataStore.data.first()[preferredPlayerKey])

    suspend fun setPreferredPlayer(player: PreferredPlayer) {
        context.dataStore.edit { prefs ->
            prefs[preferredPlayerKey] = player.storageValue
        }
    }

    suspend fun getUpdateBaseUrl(): String {
        val raw = context.dataStore.data.first()[updateBaseUrlKey]?.trim()?.takeIf { it.isNotBlank() }
            ?: DEFAULT_UPDATE_BASE_URL
        return UpdateSources.migrateShelfHost(raw)
    }

    suspend fun setUpdateBaseUrl(url: String) {
        val cleaned = UpdateSources.migrateShelfHost(
            url.trim().let { if (it.endsWith("/")) it else "$it/" }
        )
        context.dataStore.edit { prefs ->
            prefs[updateBaseUrlKey] = cleaned.ifBlank { DEFAULT_UPDATE_BASE_URL }
        }
    }

    /** Persist a saved 192.168.4.37 shelf as 192.168.4.33. No-op when unset or already new. */
    suspend fun migrateSavedShelfHost(): Boolean {
        val raw = context.dataStore.data.first()[updateBaseUrlKey]?.trim().orEmpty()
        if (raw.isBlank() || !raw.contains(UpdateSources.OLD_SHELF_HOST)) return false
        val migrated = UpdateSources.migrateShelfHost(raw)
        if (migrated == raw) return false
        context.dataStore.edit { prefs ->
            prefs[updateBaseUrlKey] = migrated
        }
        return true
    }

    suspend fun getAppearanceMode(): AppearanceMode =
        AppearanceMode.fromStorage(context.dataStore.data.first()[appearanceKey])

    suspend fun setAppearanceMode(mode: AppearanceMode) {
        context.dataStore.edit { prefs ->
            prefs[appearanceKey] = mode.storageValue
        }
    }

    suspend fun getAccentPreset(): AccentPreset =
        AccentPreset.fromStorage(context.dataStore.data.first()[accentKey])

    suspend fun setAccentPreset(preset: AccentPreset) {
        context.dataStore.edit { prefs ->
            prefs[accentKey] = preset.storageValue
        }
    }

    suspend fun getAppLayoutMode(): AppLayoutMode =
        AppLayoutMode.fromStorage(context.dataStore.data.first()[appLayoutKey])

    suspend fun setAppLayoutMode(mode: AppLayoutMode) {
        context.dataStore.edit { prefs ->
            prefs[appLayoutKey] = mode.storageValue
        }
    }

    suspend fun getPosterColumns(): Int =
        normalizePosterColumns(context.dataStore.data.first()[posterColumnsKey])

    suspend fun setPosterColumns(columns: Int) {
        context.dataStore.edit { prefs ->
            prefs[posterColumnsKey] = normalizePosterColumns(columns)
        }
    }

    suspend fun getAutoPip(): Boolean =
        context.dataStore.data.first()[autoPipKey] ?: defaultAutoPip()

    suspend fun setAutoPip(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[autoPipKey] = enabled
        }
    }

    suspend fun setSharpPosters(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[sharpPostersKey] = enabled
        }
    }

    suspend fun setTmdbRatings(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[tmdbRatingsKey] = enabled
        }
    }

    suspend fun setTmdbApiKey(key: String) {
        context.dataStore.edit { prefs ->
            val cleaned = key.trim()
            if (cleaned.isBlank()) prefs.remove(tmdbApiKeyKey) else prefs[tmdbApiKeyKey] = cleaned
        }
    }

    suspend fun getRecordingsDir(): String =
        context.dataStore.data.first()[recordingsDirKey]?.trim().orEmpty()

    suspend fun setRecordingsDir(path: String) {
        context.dataStore.edit { prefs ->
            val cleaned = path.trim()
            if (cleaned.isBlank()) prefs.remove(recordingsDirKey) else prefs[recordingsDirKey] = cleaned
        }
    }

    suspend fun saveSources(list: List<PlaylistSource>) {
        context.dataStore.edit { prefs ->
            prefs[sourcesKey] = json.encodeToString(ListSerializer(PlaylistSource.serializer()), list)
        }
    }

    suspend fun addSource(source: PlaylistSource) {
        val current = getSources().toMutableList()
        current.removeAll { it.id == source.id }
        current.add(source)
        saveSources(current)
        setActiveSource(source.id)
    }

    suspend fun removeSource(id: String) {
        val current = getSources().filterNot { it.id == id }
        saveSources(current)
        val active = getActiveSourceId()
        if (active == id) {
            setActiveSource(current.firstOrNull()?.id)
        }
    }

    suspend fun setActiveSource(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(activeSourceKey) else prefs[activeSourceKey] = id
        }
    }

    suspend fun toggleFavorite(item: FavoriteRef) {
        val current = getFavorites().toMutableList()
        val existing = current.indexOfFirst { it.id == item.id }
        if (existing >= 0) current.removeAt(existing) else current.add(item)
        context.dataStore.edit { prefs ->
            prefs[favoritesKey] = json.encodeToString(ListSerializer(FavoriteRef.serializer()), current)
        }
    }

    suspend fun isFavorite(id: String): Boolean = getFavorites().any { it.id == id }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    private fun decodeList(raw: String): List<PlaylistSource> =
        runCatching { json.decodeFromString(ListSerializer(PlaylistSource.serializer()), raw) }
            .getOrDefault(emptyList())

    private fun decodeFavs(raw: String): List<FavoriteRef> =
        runCatching { json.decodeFromString(ListSerializer(FavoriteRef.serializer()), raw) }
            .getOrDefault(emptyList())
}
