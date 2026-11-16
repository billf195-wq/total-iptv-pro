package com.totaliptv.pro.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LicensePolicyTest {
    private val day = LicensePolicy.MILLIS_PER_DAY
    private val start = 1_700_000_000_000L

    @Test
    fun productIdAndPriceAreTheAgreedValues() {
        assertEquals("tip_unlock", LicensePolicy.PRODUCT_ID)
        assertEquals("$4.99", LicensePolicy.DISPLAY_PRICE)
        assertEquals(30, LicensePolicy.TRIAL_DAYS)
    }

    @Test
    fun freshInstallStartsTrialClockAndIsNotGrandfathered() {
        val migrated = LicensePolicy.migrate(LicenseRecord(), hasExistingAppData = false, nowMillis = start)
        assertTrue(migrated.migrated)
        assertFalse(migrated.grandfathered)
        assertEquals(start, migrated.firstSeenAtMillis)
        val status = LicensePolicy.evaluate(migrated, PurchaseLookup.NotOwned, start)
        val trial = assertIs<LicenseStatus.Trial>(status)
        assertEquals(30, trial.daysLeft)
        assertTrue(trial.allowsUse)
        assertTrue(trial.showBuyButton)
    }

    @Test
    fun existingAppDataIsGrandfatheredAndStaysFree() {
        val migrated = LicensePolicy.migrate(LicenseRecord(), hasExistingAppData = true, nowMillis = start)
        assertTrue(migrated.grandfathered)
        val later = start + 400 * day
        val status = LicensePolicy.evaluate(migrated, PurchaseLookup.NotOwned, later)
        assertEquals(LicenseStatus.Unlocked, status)
        assertTrue(status.allowsUse)
        assertFalse(status.showBuyButton)
    }

    @Test
    fun migrationIsIdempotent() {
        val first = LicensePolicy.migrate(LicenseRecord(), hasExistingAppData = false, nowMillis = start)
        val second = LicensePolicy.migrate(first, hasExistingAppData = true, nowMillis = start + day)
        assertEquals(first, second)
        assertFalse(second.grandfathered)
        assertEquals(start, second.firstSeenAtMillis)
    }

    @Test
    fun daysLeftCeilingsPartialDaysAndHitsZeroAtExpiry() {
        val firstSeen = start
        assertEquals(30, LicensePolicy.daysLeft(firstSeen, start))
        assertEquals(30, LicensePolicy.daysLeft(firstSeen, start + 1))
        assertEquals(29, LicensePolicy.daysLeft(firstSeen, start + day))
        assertEquals(1, LicensePolicy.daysLeft(firstSeen, start + 29 * day + 1))
        assertEquals(1, LicensePolicy.daysLeft(firstSeen, start + 30 * day - 1))
        assertEquals(0, LicensePolicy.daysLeft(firstSeen, start + 30 * day))
        assertEquals(0, LicensePolicy.daysLeft(firstSeen, start + 31 * day))
    }

    @Test
    fun midTrialKeepsAccessAndShowsBuy() {
        val record = LicensePolicy.migrate(LicenseRecord(), false, start)
        val status = LicensePolicy.evaluate(record, PurchaseLookup.Unknown, start + 10 * day)
        val trial = assertIs<LicenseStatus.Trial>(status)
        assertEquals(20, trial.daysLeft)
        assertTrue(status.allowsUse)
        assertTrue(status.showBuyButton)
    }

    @Test
    fun expiredAndNotOwnedBlocksUse() {
        val record = LicensePolicy.migrate(LicenseRecord(), false, start)
        val status = LicensePolicy.evaluate(record, PurchaseLookup.NotOwned, start + 30 * day)
        assertEquals(LicenseStatus.Expired, status)
        assertFalse(status.allowsUse)
        assertTrue(status.showBuyButton)
    }

    @Test
    fun expiredButOfflineFailsOpen() {
        val record = LicensePolicy.migrate(LicenseRecord(), false, start)
        val status = LicensePolicy.evaluate(record, PurchaseLookup.Unknown, start + 40 * day)
        assertEquals(LicenseStatus.FailOpen, status)
        assertTrue(status.allowsUse)
        assertTrue(status.showBuyButton)
    }

    @Test
    fun livePurchaseUnlocksEvenWithoutCache() {
        val record = LicensePolicy.migrate(LicenseRecord(), false, start)
        val status = LicensePolicy.evaluate(record, PurchaseLookup.Owned, start + 40 * day)
        assertEquals(LicenseStatus.Unlocked, status)
        assertTrue(status.allowsUse)
        assertFalse(status.showBuyButton)
    }

    @Test
    fun cachedPurchaseUnlocksWhenPlayIsUnreachable() {
        val record = LicensePolicy.migrate(LicenseRecord(), false, start).copy(purchasedCached = true)
        val status = LicensePolicy.evaluate(record, PurchaseLookup.Unknown, start + 40 * day)
        assertEquals(LicenseStatus.Unlocked, status)
    }

    @Test
    fun missingFirstSeenFailsOpenRatherThanLockingOut() {
        val record = LicenseRecord(migrated = true, grandfathered = false, firstSeenAtMillis = null)
        val status = LicensePolicy.evaluate(record, PurchaseLookup.NotOwned, start)
        assertEquals(LicenseStatus.FailOpen, status)
        assertTrue(status.allowsUse)
    }

    @Test
    fun checkingStatusAllowsUseAndHidesBuy() {
        assertTrue(LicenseStatus.Checking.allowsUse)
        assertFalse(LicenseStatus.Checking.showBuyButton)
    }
}
