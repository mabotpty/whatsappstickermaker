package za.co.madbot.stickermaker

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase as PlayPurchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Google Play Billing for the one-time product [PRODUCT_ID] (the id must match the in-app product in Play Console and the server's
 * STICKER_PLAY_PRODUCT_ID). The server acknowledges the purchase after it creates the licence, so the app does not.
 */
class PlayBilling(private val activity: Activity) : Billing {
    companion object { const val PRODUCT_ID = "unlock" }

    private var waiting: CompletableDeferred<Purchase>? = null

    private val listener = PurchasesUpdatedListener { result, purchases ->
        val w = waiting ?: return@PurchasesUpdatedListener
        waiting = null
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val p = purchases?.firstOrNull { PRODUCT_ID in it.products }
                w.complete(
                    when {
                        p == null -> Purchase.Cancelled
                        p.purchaseState == PlayPurchase.PurchaseState.PENDING -> Purchase.Pending
                        else -> Purchase.Owned(p.purchaseToken)
                    },
                )
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> w.complete(Purchase.Cancelled)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> w.complete(Purchase.Cancelled) // owned() finds it on the next Restore
            else -> w.completeExceptionally(LicenceException("billing_" + result.responseCode, result.debugMessage.ifEmpty { "Google Play could not complete the purchase." }))
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(activity.applicationContext)
        .setListener(listener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private suspend fun connect() {
        if (client.isReady) return
        val r = suspendCancellableCoroutine<BillingResult> { cont ->
            client.startConnection(object : com.android.billingclient.api.BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) { if (cont.isActive) cont.resume(result) }
                override fun onBillingServiceDisconnected() {}
            })
        }
        if (r.responseCode != BillingClient.BillingResponseCode.OK) throw IOException("Google Play is not available on this phone (${r.debugMessage}).")
    }

    private fun productParams() = QueryProductDetailsParams.newBuilder().setProductList(
        listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build()),
    ).build()

    override suspend fun price(): String? = try {
        connect()
        client.queryProductDetails(productParams()).productDetailsList?.firstOrNull()?.oneTimePurchaseOfferDetails?.formattedPrice
    } catch (e: Exception) { null }

    override suspend fun owned(): String? {
        connect()
        val r = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build())
        if (r.billingResult.responseCode != BillingClient.BillingResponseCode.OK) throw IOException("Google Play could not list purchases.")
        return r.purchasesList.firstOrNull { PRODUCT_ID in it.products && it.purchaseState == PlayPurchase.PurchaseState.PURCHASED }?.purchaseToken
    }

    override suspend fun buy(): Purchase {
        connect()
        val details = client.queryProductDetails(productParams()).productDetailsList?.firstOrNull()
            ?: throw LicenceException("not_available", "The unlock is not available on Google Play yet.")
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()),
        ).build()
        val w = CompletableDeferred<Purchase>()
        waiting = w
        val started = client.launchBillingFlow(activity, flow)
        if (started.responseCode != BillingClient.BillingResponseCode.OK) {
            waiting = null
            throw LicenceException("billing_" + started.responseCode, started.debugMessage.ifEmpty { "Google Play could not start the purchase." })
        }
        return w.await()
    }

    fun close() { client.endConnection() }
}
