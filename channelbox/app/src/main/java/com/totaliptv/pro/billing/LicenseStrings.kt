package com.totaliptv.pro.billing

/**
 * User-facing license copy. Kept as Kotlin constants, the same way the rest of
 * the Android app labels Settings rows and player chrome (almost no string
 * resources, English in code).
 */
object LicenseStrings {
    const val SECTION = "Unlock"
    const val UNLOCKED = "Unlocked"
    const val TRIAL_ENDED = "Your 30-day free period has ended"
    const val BUY_SCREEN_TITLE = "Unlock Total IPTV Pro"
    const val BUY_SCREEN_BODY =
        "Your 30-day free period has ended. Unlock the app for a one-time payment of $4.99."
    const val FAIL_OPEN_NOTE =
        "Couldn't reach Google Play. You can keep using the app. Buy to unlock when you are online."
    const val RESTORE = "Restore purchase"
    const val CHECKING = "Checking license…"
    const val BUY_FAILED = "Couldn't start the purchase. Try again when you're online."
    const val RESTORE_NONE = "No purchase found for this Google account."
    const val RESTORE_OK = "Purchase restored."
    const val PURCHASE_PENDING = "Purchase is pending. You'll be unlocked when Google Play finishes it."

    fun daysLeft(days: Int): String =
        if (days == 1) "1 day left" else "$days days left"

    fun buyLabel(price: String = LicensePolicy.DISPLAY_PRICE): String = "Buy — $price"

    fun statusLine(status: LicenseStatus, price: String = LicensePolicy.DISPLAY_PRICE): String =
        when (status) {
            LicenseStatus.Checking -> CHECKING
            LicenseStatus.Unlocked -> UNLOCKED
            is LicenseStatus.Trial -> daysLeft(status.daysLeft)
            LicenseStatus.Expired -> TRIAL_ENDED
            LicenseStatus.FailOpen -> FAIL_OPEN_NOTE
        }.let { it /* price reserved for the Buy button, not this line */ }
}
