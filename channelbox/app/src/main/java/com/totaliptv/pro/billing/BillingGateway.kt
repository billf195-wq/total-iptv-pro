package com.totaliptv.pro.billing

import android.app.Activity

/** Result of asking the store whether the unlock product is owned. */
sealed class PurchaseQueryResult {
    data object Owned : PurchaseQueryResult()
    data object NotOwned : PurchaseQueryResult()
    data object Unknown : PurchaseQueryResult()
}

data class UnlockProduct(
    val productId: String,
    val formattedPrice: String
)

/**
 * Narrow wrapper around Play Billing so [LicenseController] and unit tests
 * don't talk to [com.android.billingclient.api.BillingClient] directly.
 */
interface BillingGateway {
    fun setPurchaseListener(listener: (PurchaseEvent) -> Unit)

    /** True when a connection to Google Play was established. */
    suspend fun connect(): Boolean

    suspend fun queryUnlock(): PurchaseQueryResult

    suspend fun loadUnlockProduct(): UnlockProduct?

    /** Starts the Play purchase sheet. Returns false if it could not be launched. */
    suspend fun launchBuy(activity: Activity): Boolean

    suspend fun acknowledgeIfNeeded()

    fun close()
}

sealed class PurchaseEvent {
    data object Purchased : PurchaseEvent()
    data object Pending : PurchaseEvent()
    data object Canceled : PurchaseEvent()
    data class Failed(val message: String) : PurchaseEvent()
}
