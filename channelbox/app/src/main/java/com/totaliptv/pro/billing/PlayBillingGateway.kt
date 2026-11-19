package com.totaliptv.pro.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Google Play Billing Library (Apache-2.0) gateway for the one-time
 * [LicensePolicy.PRODUCT_ID] unlock. Uses the Java BillingClient API so the
 * existing Kotlin 2.0 toolchain stays compatible. Failures return Unknown /
 * false so the license policy can fail open.
 */
class PlayBillingGateway(context: Context) : BillingGateway, PurchasesUpdatedListener {

    private var purchaseListener: ((PurchaseEvent) -> Unit)? = null
    private var cachedDetails: ProductDetails? = null

    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    override fun setPurchaseListener(listener: (PurchaseEvent) -> Unit) {
        purchaseListener = listener
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (billingClient.isReady) return@withContext true
        suspendCancellableCoroutine { cont ->
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (cont.isActive) {
                        cont.resume(billingResult.responseCode == BillingClient.BillingResponseCode.OK)
                    }
                }

                override fun onBillingServiceDisconnected() {
                    // Auto reconnect is enabled. Leave this empty.
                }
            })
        }
    }

    override suspend fun queryUnlock(): PurchaseQueryResult = withContext(Dispatchers.IO) {
        if (!connect()) return@withContext PurchaseQueryResult.Unknown
        runCatching {
            val (result, purchases) = queryPurchases()
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                return@withContext PurchaseQueryResult.Unknown
            }
            val owned = purchases.any { it.ownsUnlock() }
            if (owned) PurchaseQueryResult.Owned else PurchaseQueryResult.NotOwned
        }.getOrDefault(PurchaseQueryResult.Unknown)
    }

    override suspend fun loadUnlockProduct(): UnlockProduct? = withContext(Dispatchers.IO) {
        if (!connect()) return@withContext null
        val details = queryDetails() ?: return@withContext null
        cachedDetails = details
        UnlockProduct(
            productId = details.productId,
            formattedPrice = details.formattedPrice() ?: LicensePolicy.DISPLAY_PRICE
        )
    }

    override suspend fun launchBuy(activity: Activity): Boolean = withContext(Dispatchers.Main) {
        if (!connect()) return@withContext false
        val details = cachedDetails ?: queryDetails() ?: return@withContext false
        cachedDetails = details
        val offerToken = details.unlockOfferToken()
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .let { builder ->
                if (!offerToken.isNullOrBlank()) builder.setOfferToken(offerToken) else builder
            }
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val launched = billingClient.launchBillingFlow(activity, flowParams)
        launched.responseCode == BillingClient.BillingResponseCode.OK
    }

    override suspend fun acknowledgeIfNeeded() {
        if (!connect()) return
        runCatching {
            val (result, purchases) = queryPurchases()
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return
            purchases
                .filter { it.ownsUnlock() && !it.isAcknowledged }
                .forEach { purchase ->
                    acknowledge(purchase.purchaseToken)
                }
        }
    }

    override fun close() {
        runCatching { billingClient.endConnection() }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val list = purchases.orEmpty()
                when {
                    list.any { it.ownsUnlock() } -> purchaseListener?.invoke(PurchaseEvent.Purchased)
                    list.any {
                        it.products.contains(LicensePolicy.PRODUCT_ID) &&
                            it.purchaseState == Purchase.PurchaseState.PENDING
                    } -> purchaseListener?.invoke(PurchaseEvent.Pending)
                    else -> purchaseListener?.invoke(PurchaseEvent.Failed("Purchase did not complete."))
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                purchaseListener?.invoke(PurchaseEvent.Canceled)
            else -> purchaseListener?.invoke(PurchaseEvent.Failed("Purchase did not complete."))
        }
    }

    private suspend fun queryPurchases(): Pair<BillingResult, List<Purchase>> =
        suspendCancellableCoroutine { cont ->
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build(),
                PurchasesResponseListener { result, purchases ->
                    if (cont.isActive) cont.resume(result to purchases.orEmpty())
                }
            )
        }

    private suspend fun queryDetails(): ProductDetails? =
        suspendCancellableCoroutine { cont ->
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(LicensePolicy.PRODUCT_ID)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    )
                )
                .build()
            billingClient.queryProductDetailsAsync(params) { result, queryResult ->
                if (!cont.isActive) return@queryProductDetailsAsync
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    cont.resume(null)
                    return@queryProductDetailsAsync
                }
                val list = queryResult.productDetailsList.orEmpty()
                cont.resume(list.firstOrNull { it.productId == LicensePolicy.PRODUCT_ID })
            }
        }

    private suspend fun acknowledge(token: String) {
        suspendCancellableCoroutine { cont ->
            billingClient.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(token)
                    .build(),
                AcknowledgePurchaseResponseListener {
                    if (cont.isActive) cont.resume(Unit)
                }
            )
        }
    }

    private fun Purchase.ownsUnlock(): Boolean =
        products.contains(LicensePolicy.PRODUCT_ID) &&
            purchaseState == Purchase.PurchaseState.PURCHASED

    private fun ProductDetails.formattedPrice(): String? {
        runCatching {
            val list = oneTimePurchaseOfferDetailsList
            if (!list.isNullOrEmpty()) {
                list.firstOrNull()?.formattedPrice?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return runCatching { oneTimePurchaseOfferDetails?.formattedPrice }
            .getOrNull()
            ?.takeIf { !it.isNullOrBlank() }
    }

    private fun ProductDetails.unlockOfferToken(): String? {
        runCatching {
            val list = oneTimePurchaseOfferDetailsList
            if (!list.isNullOrEmpty()) {
                list.firstOrNull()?.offerToken?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return runCatching { oneTimePurchaseOfferDetails?.offerToken }
            .getOrNull()
            ?.takeIf { !it.isNullOrBlank() }
    }
}
