package com.totaliptv.pro.billing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.totaliptv.pro.data.local.AppPreferences
import kotlinx.coroutines.flow.first

private val Context.tipLicenseStore: DataStore<Preferences> by preferencesDataStore(name = "tip_license")

/**
 * License flags live in their own DataStore so Settings "Clear all data" does
 * not wipe a purchase or a grandfathered grant.
 */
class LicenseStore(
    private val context: Context,
    private val catalogKeys: suspend () -> Set<String> = {
        // Read the catalog DataStore through a one-shot preferences load.
        // AppPreferences does not expose raw keys, so we also accept a probe
        // from ExistingAppData which checks files and known stores.
        emptySet()
    }
) {
    private val dataStore: DataStore<Preferences> = context.applicationContext.tipLicenseStore

    private val migratedKey = booleanPreferencesKey(LicenseRecord.KEY_MIGRATED)
    private val grandfatheredKey = booleanPreferencesKey(LicenseRecord.KEY_GRANDFATHERED)
    private val firstSeenKey = longPreferencesKey(LicenseRecord.KEY_FIRST_SEEN)
    private val purchasedKey = booleanPreferencesKey(LicenseRecord.KEY_PURCHASED)

    suspend fun read(): LicenseRecord {
        val prefs = dataStore.data.first()
        return LicenseRecord(
            migrated = prefs[migratedKey] ?: false,
            grandfathered = prefs[grandfatheredKey] ?: false,
            firstSeenAtMillis = prefs[firstSeenKey],
            purchasedCached = prefs[purchasedKey] ?: false
        )
    }

    suspend fun write(record: LicenseRecord) {
        dataStore.edit { prefs ->
            prefs[migratedKey] = record.migrated
            prefs[grandfatheredKey] = record.grandfathered
            val first = record.firstSeenAtMillis
            if (first == null) prefs.remove(firstSeenKey) else prefs[firstSeenKey] = first
            prefs[purchasedKey] = record.purchasedCached
        }
    }

    suspend fun markPurchased() {
        dataStore.edit { prefs ->
            prefs[purchasedKey] = true
        }
    }

    suspend fun catalogHasUserData(): Boolean {
        // File-based probe plus any catalog keys the caller can see.
        val keys = runCatching { catalogKeys() }.getOrDefault(emptySet())
        return ExistingAppData.presentOnDevice(context, keys)
    }

    companion object {
        /** Used by tests and by a catalog-aware probe. */
        fun hasCatalogUserData(preferences: AppPreferences, extraKeys: Set<String>): Boolean =
            extraKeys.any { it !in LicensePolicy.LICENSE_PREF_KEYS }
    }
}
