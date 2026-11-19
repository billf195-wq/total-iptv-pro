package com.totaliptv.pro.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicenseStringsTest {
    @Test
    fun daysLeftUsesSingularOnTheLastDay() {
        assertEquals("1 day left", LicenseStrings.daysLeft(1))
        assertEquals("2 days left", LicenseStrings.daysLeft(2))
        assertEquals("30 days left", LicenseStrings.daysLeft(30))
    }

    @Test
    fun buyLabelIncludesThePrice() {
        assertEquals("Buy — $4.99", LicenseStrings.buyLabel())
        assertEquals("Buy — $4.99", LicenseStrings.buyLabel("$4.99"))
    }

    @Test
    fun statusLineMatchesEachLicenseState() {
        assertEquals("Unlocked", LicenseStrings.statusLine(LicenseStatus.Unlocked))
        assertEquals("30 days left", LicenseStrings.statusLine(LicenseStatus.Trial(30, 0L)))
        assertEquals("1 day left", LicenseStrings.statusLine(LicenseStatus.Trial(1, 0L)))
        assertTrue(LicenseStrings.statusLine(LicenseStatus.Expired).contains("ended"))
        assertTrue(LicenseStrings.statusLine(LicenseStatus.FailOpen).contains("Google Play"))
        assertEquals(LicenseStrings.CHECKING, LicenseStrings.statusLine(LicenseStatus.Checking))
    }

    @Test
    fun buyScreenCopyMentionsThePriceAndRestore() {
        assertTrue(LicenseStrings.BUY_SCREEN_BODY.contains("$4.99"))
        assertEquals("Restore purchase", LicenseStrings.RESTORE)
        assertFalse(LicenseStrings.BUY_SCREEN_BODY.contains("39.99"))
    }
}
