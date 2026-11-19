package com.totaliptv.pro.billing

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LicenseController(
    private val store: LicenseStore,
    private val billing: BillingGateway,
    private val hasExistingAppData: suspend () -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<LicenseStatus>(LicenseStatus.Checking)
    val status: StateFlow<LicenseStatus> = _status.asStateFlow()

    private val _price = MutableStateFlow(LicensePolicy.DISPLAY_PRICE)
    val price: StateFlow<String> = _price.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        billing.setPurchaseListener { event ->
            scope.launch {
                when (event) {
                    PurchaseEvent.Purchased -> {
                        store.markPurchased()
                        billing.acknowledgeIfNeeded()
                        _message.value = LicenseStrings.RESTORE_OK
                        refresh()
                    }
                    PurchaseEvent.Pending -> {
                        _message.value = LicenseStrings.PURCHASE_PENDING
                    }
                    PurchaseEvent.Canceled -> Unit
                    is PurchaseEvent.Failed -> {
                        _message.value = LicenseStrings.BUY_FAILED
                    }
                }
            }
        }
    }

    fun start() {
        scope.launch { refresh() }
    }

    fun refreshInBackground() {
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        mutex.withLock {
            val now = clock()
            var record = store.read()
            if (!record.migrated) {
                record = LicensePolicy.migrate(record, hasExistingAppData(), now)
                store.write(record)
            }
            val connected = runCatching { billing.connect() }.getOrDefault(false)
            val query = if (connected) {
                runCatching { billing.queryUnlock() }.getOrDefault(PurchaseQueryResult.Unknown)
            } else {
                PurchaseQueryResult.Unknown
            }
            if (query is PurchaseQueryResult.Owned) {
                store.markPurchased()
                runCatching { billing.acknowledgeIfNeeded() }
                record = store.read()
            }
            runCatching {
                billing.loadUnlockProduct()?.formattedPrice?.let { _price.value = it }
            }
            val lookup = when (query) {
                PurchaseQueryResult.Owned -> PurchaseLookup.Owned
                PurchaseQueryResult.NotOwned -> PurchaseLookup.NotOwned
                PurchaseQueryResult.Unknown -> PurchaseLookup.Unknown
            }
            _status.value = LicensePolicy.evaluate(record, lookup, now)
        }
    }

    suspend fun buy(activity: Activity): Boolean {
        val ok = runCatching { billing.launchBuy(activity) }.getOrDefault(false)
        if (!ok) _message.value = LicenseStrings.BUY_FAILED
        return ok
    }

    suspend fun restore(): Boolean {
        refresh()
        val unlocked = _status.value is LicenseStatus.Unlocked
        _message.value = if (unlocked) LicenseStrings.RESTORE_OK else LicenseStrings.RESTORE_NONE
        return unlocked
    }

    fun close() {
        billing.close()
    }

    companion object {
        fun create(context: Context, existingData: suspend () -> Boolean): LicenseController =
            LicenseController(
                store = LicenseStore(context),
                billing = PlayBillingGateway(context),
                hasExistingAppData = existingData
            )
    }
}
