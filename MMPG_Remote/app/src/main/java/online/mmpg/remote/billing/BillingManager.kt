package online.mmpg.remote.billing

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Dono da conexão com o Google Play Billing para os dois produtos premium
 * (vitalício/INAPP e assinatura mensal/SUBS). O preço nunca é fixado aqui —
 * vem sempre do que estiver configurado para esses IDs no Play Console,
 * buscado ao vivo em [fetchPrices]; os valores combinados (R$ 24,90 /
 * R$ 3,90) só existem de fato depois de cadastrados lá.
 *
 * Vive no mesmo ciclo de vida da Activity (criado em onCreate, fechado em
 * onDestroy) porque launchBillingFlow exige uma Activity real — por isso
 * esta classe recebe a Activity diretamente, ao contrário de TvBridge, que
 * só guarda applicationContext.
 */
class BillingManager(
    private val activity: Activity,
    private val diag: (String) -> Unit,
    private val onEntitlementChanged: (JSONObject) -> Unit,
    private val onPurchaseEvent: (JSONObject) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                scope.launch {
                    purchases?.forEach { handlePurchase(it) }
                    val entitlement = queryEntitlement()
                    onEntitlementChanged(entitlement)
                    onPurchaseEvent(JSONObject().apply {
                        put("ok", true)
                        put("code", "PURCHASED")
                        put("premium", entitlement.optBoolean("premium"))
                    })
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                diag("Compra cancelada pelo usuário")
                onPurchaseEvent(JSONObject().put("ok", false).put("code", "USER_CANCELED"))
            }
            else -> {
                diag("Falha na compra: código ${billingResult.responseCode}")
                onPurchaseEvent(JSONObject().put("ok", false).put("code", billingResult.responseCode.toString()))
            }
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(activity.applicationContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    @Volatile
    private var connected = false

    fun connect() {
        if (connected) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                connected = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                diag(
                    if (connected) "BillingClient conectado"
                    else "Falha ao conectar BillingClient: código ${billingResult.responseCode}"
                )
                if (connected) {
                    scope.launch { onEntitlementChanged(queryEntitlement()) }
                }
            }

            override fun onBillingServiceDisconnected() {
                connected = false
                diag("BillingClient desconectado (reconexão automática ativa)")
            }
        })
    }

    /**
     * {"premium": bool, "source": "lifetime"|"subscription"|null}
     *
     * Envolvida em [withTimeoutOrNull]: se o BillingClient nunca chegar a
     * conectar (ex.: `DEVELOPER_ERROR` porque os produtos ainda não existem
     * no Play Console) e `enableAutoServiceReconnection()` ficar tentando de
     * novo indefinidamente, uma chamada `queryPurchasesAsync` feita nesse
     * meio-tempo pode nunca invocar seu callback - sem este timeout, esta
     * função suspenderia para sempre, o evento "entitlement" nunca chegaria
     * à WebView, e o paywall (que só conta sessão depois de receber esse
     * evento - ver paywall.js) nunca teria uma chance real de disparar,
     * mesmo com a cadência mais agressiva. Descoberto testando contra um
     * dispositivo real sem os produtos ainda cadastrados.
     */
    suspend fun queryEntitlement(): JSONObject {
        val outcome = withTimeoutOrNull(QUERY_TIMEOUT_MS) {
            val lifetime = queryPurchases(BillingClient.ProductType.INAPP)
                .any { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.contains(PRODUCT_LIFETIME) }
            val subscription = queryPurchases(BillingClient.ProductType.SUBS)
                .any { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.contains(PRODUCT_MONTHLY) }
            lifetime to subscription
        }
        if (outcome == null) diag("Consulta de entitlement expirou (BillingClient indisponível?) - tratando como não-premium")
        val (lifetime, subscription) = outcome ?: (false to false)
        return JSONObject().apply {
            put("premium", lifetime || subscription)
            put(
                "source",
                when {
                    lifetime -> "lifetime"
                    subscription -> "subscription"
                    else -> JSONObject.NULL
                }
            )
        }
    }

    /** {"lifetime": {"productId","price"}, "monthly": {"productId","price"}} — preço já formatado/localizado. Mesma proteção de timeout de [queryEntitlement]. */
    suspend fun fetchPrices(): JSONObject {
        val lifetimeDetails = withTimeoutOrNull(QUERY_TIMEOUT_MS) { fetchProductDetails(PRODUCT_LIFETIME, BillingClient.ProductType.INAPP) }
        val monthlyDetails = withTimeoutOrNull(QUERY_TIMEOUT_MS) { fetchProductDetails(PRODUCT_MONTHLY, BillingClient.ProductType.SUBS) }
        return JSONObject().apply {
            put("lifetime", JSONObject().apply {
                put("productId", PRODUCT_LIFETIME)
                put("price", lifetimeDetails?.oneTimePurchaseOfferDetails?.formattedPrice ?: "")
            })
            put("monthly", JSONObject().apply {
                put("productId", PRODUCT_MONTHLY)
                put(
                    "price",
                    monthlyDetails?.subscriptionOfferDetails?.firstOrNull()
                        ?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice ?: ""
                )
            })
        }
    }

    /**
     * Abre o fluxo de compra do Play. O resultado desta função só informa se
     * o fluxo PÔDE ser aberto — o desfecho real (comprado/cancelado/erro)
     * chega depois, de forma assíncrona, via [purchasesUpdatedListener]
     * acima (repassado como evento "purchaseResult" por quem instanciar
     * esta classe).
     */
    suspend fun launchPurchase(productId: String): JSONObject {
        val type = if (productId == PRODUCT_MONTHLY) BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP
        val details = withTimeoutOrNull(QUERY_TIMEOUT_MS) { fetchProductDetails(productId, type) }
            ?: return JSONObject().put("ok", false).put("code", "PRODUCT_NOT_FOUND")

        val paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
        if (type == BillingClient.ProductType.SUBS) {
            val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
                ?: return JSONObject().put("ok", false).put("code", "NO_OFFER")
            paramsBuilder.setOfferToken(offerToken)
        } else {
            details.oneTimePurchaseOfferDetails?.offerToken?.let { paramsBuilder.setOfferToken(it) }
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(paramsBuilder.build()))
            .build()

        val result = withContext(Dispatchers.Main) {
            client.launchBillingFlow(activity, flowParams)
        }
        return JSONObject().apply {
            put("ok", result.responseCode == BillingClient.BillingResponseCode.OK)
            put("code", result.responseCode.toString())
        }
    }

    private suspend fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        diag("Compra recebida para ${purchase.products.joinToString()} (acknowledged=${purchase.isAcknowledged})")
        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            suspendCancellableCoroutine<Unit> { cont ->
                client.acknowledgePurchase(params) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            diag("Compra confirmada (acknowledge) para ${purchase.products.joinToString()}")
        }
    }

    private suspend fun fetchProductDetails(productId: String, type: String): ProductDetails? =
        suspendCancellableCoroutine { cont ->
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(type)
                            .build()
                    )
                )
                .build()
            client.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
                if (!cont.isActive) return@queryProductDetailsAsync
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val found = queryProductDetailsResult.productDetailsList.firstOrNull()
                    if (found == null) diag("Produto $productId não encontrado (cheque o cadastro no Play Console)")
                    cont.resume(found)
                } else {
                    diag("Erro ao consultar produto $productId: código ${billingResult.responseCode}")
                    cont.resume(null)
                }
            }
        }

    private suspend fun queryPurchases(type: String): List<Purchase> = suspendCancellableCoroutine { cont ->
        val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
        client.queryPurchasesAsync(params) { billingResult, purchases ->
            if (!cont.isActive) return@queryPurchasesAsync
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                cont.resume(purchases)
            } else {
                cont.resume(emptyList())
            }
        }
    }

    fun close() {
        scope.cancel()
        client.endConnection()
    }

    companion object {
        const val PRODUCT_LIFETIME = "mmpg_remote_lifetime"
        const val PRODUCT_MONTHLY = "mmpg_remote_mensal"
        private const val QUERY_TIMEOUT_MS = 5_000L
    }
}
