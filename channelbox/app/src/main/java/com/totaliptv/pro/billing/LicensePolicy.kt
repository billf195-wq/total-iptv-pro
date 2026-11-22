package com.totaliptv.pro.billing

/**
 * Pure license rules for the Play unlock. No Android or Billing Client here so
 * unit tests can cover every path.
 *
 * New installs get a 30-day free period from first launch of a billing-aware
 * build. Existing app data from before that update is grandfathered and stays
 * free. A cached or live Play purchase of [PRODUCT_ID] unlocks forever.
 * If Play cannot be reached after the trial, access stays open (fail open).
 */
object LicensePolicy {
    const val TRIAL_DAYS = 30
    const val PRODUCT_ID = "tip_unlock"
    const val DISPLAY_PRICE = "$4.99"
    const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    val LICENSE_PREF_KEYS: Set<String> = setOf(
        LicenseRecord.KEY_MIGRATED,
        LicenseRecord.KEY_GRANDFATHERED,
        LicenseRecord.KEY_FIRST_SEEN,
        LicenseRecord.KEY_PURCHASED
    )

    fun trialEndMillis(firstSeenAtMillis: Long): Long =
        firstSeenAtMillis + TRIAL_DAYS * MILLIS_PER_DAY

    /** Whole days still remaining, 1 through [TRIAL_DAYS]. Zero means the trial has ended. */
    fun daysLeft(firstSeenAtMillis: Long, nowMillis: Long): Int {
        val remaining = trialEndMillis(firstSeenAtMillis) - nowMillis
        if (remaining <= 0L) return 0
        val days = ((remaining + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY).toInt()
        return days.coerceIn(1, TRIAL_DAYS)
    }

    /**
     * One-time migration: if this device already had app data, mark it
     * grandfathered. Otherwise start the 30-day clock at [nowMillis].
     */
    fun migrate(record: LicenseRecord, hasExistingAppData: Boolean, nowMillis: Long): LicenseRecord {
        if (record.migrated) return record
        return if (hasExistingAppData) {
            record.copy(
                migrated = true,
                grandfathered = true,
                firstSeenAtMillis = record.firstSeenAtMillis ?: nowMillis,
                purchasedCached = record.purchasedCached
            )
        } else {
            record.copy(
                migrated = true,
                grandfathered = false,
                firstSeenAtMillis = record.firstSeenAtMillis ?: nowMillis
            )
        }
    }

    fun evaluate(
        record: LicenseRecord,
        purchase: PurchaseLookup,
        nowMillis: Long,
        enforce: Boolean = true
    ): LicenseStatus {
        if (!enforce) return LicenseStatus.Unlocked
        val purchased = record.purchasedCached || purchase is PurchaseLookup.Owned
        if (record.grandfathered || purchased) return LicenseStatus.Unlocked
        val firstSeen = record.firstSeenAtMillis
        if (firstSeen == null) {
            // Migration should have set a clock. Fail open rather than lock the user out.
            return LicenseStatus.FailOpen
        }
        val left = daysLeft(firstSeen, nowMillis)
        if (left > 0) {
            return LicenseStatus.Trial(daysLeft = left, endsAtMillis = trialEndMillis(firstSeen))
        }
        return when (purchase) {
            PurchaseLookup.Owned -> LicenseStatus.Unlocked
            PurchaseLookup.NotOwned -> LicenseStatus.Expired
            PurchaseLookup.Unknown -> LicenseStatus.FailOpen
        }
    }
}

data class LicenseRecord(
    val migrated: Boolean = false,
    val grandfathered: Boolean = false,
    val firstSeenAtMillis: Long? = null,
    val purchasedCached: Boolean = false
) {
    companion object {
        const val KEY_MIGRATED = "license_migrated"
        const val KEY_GRANDFATHERED = "license_grandfathered"
        const val KEY_FIRST_SEEN = "license_first_seen_at"
        const val KEY_PURCHASED = "license_purchased"
    }
}

/** Result of asking Google Play whether [LicensePolicy.PRODUCT_ID] is owned. */
sealed class PurchaseLookup {
    data object Owned : PurchaseLookup()
    data object NotOwned : PurchaseLookup()
    /** Offline, Play unavailable, or the query failed. Fail open if the trial has ended. */
    data object Unknown : PurchaseLookup()
}

sealed class LicenseStatus {
    /** Still reading local data / Play. The app stays usable. */
    data object Checking : LicenseStatus()

    /** Purchased or grandfathered. */
    data object Unlocked : LicenseStatus()

    data class Trial(val daysLeft: Int, val endsAtMillis: Long) : LicenseStatus()

    /** Trial ended and Play said the product is not owned. Show the Buy screen. */
    data object Expired : LicenseStatus()

    /** Trial ended but Play could not be reached. The app stays usable. */
    data object FailOpen : LicenseStatus()

    val allowsUse: Boolean get() = this !is Expired

    val showBuyButton: Boolean
        get() = this is Trial || this is Expired || this is FailOpen
}
