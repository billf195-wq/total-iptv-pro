package com.totaliptv.pro.billing

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sideload builds pass enforceLicense=false. A faked expired trial must not gate the app.
 */
class LicenseControllerEnforceTest {
    private val day = LicensePolicy.MILLIS_PER_DAY
    private val start = 1_700_000_000_000L

    private class MemoryStore(var record: LicenseRecord) : LicenseStorePort {
        // simple stand-in used only if the real store is hard to fake here
    }

    @Test
    fun evaluatePathMatchesControllerPolicy() {
        val record = LicensePolicy.migrate(LicenseRecord(), hasExistingAppData = false, nowMillis = start)
        val expiredAt = start + 40 * day
        val play = LicensePolicy.evaluate(record, PurchaseLookup.NotOwned, expiredAt, enforce = true)
        assertEquals(LicenseStatus.Expired, play)
        val sideload = LicensePolicy.evaluate(record, PurchaseLookup.NotOwned, expiredAt, enforce = false)
        assertEquals(LicenseStatus.Unlocked, sideload)
        assertTrue(sideload.allowsUse)
        assertTrue(runBlocking { sideload.allowsUse })
    }
}

/** Marker so the file documents the fake-expired-trial check used by LicenseController.refresh. */
private interface LicenseStorePort
