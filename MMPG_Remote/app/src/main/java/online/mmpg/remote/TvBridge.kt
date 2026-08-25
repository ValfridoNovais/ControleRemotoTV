package online.mmpg.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import online.mmpg.remote.billing.BillingManager
import online.mmpg.remote.protocol.ProtocolResult
import online.mmpg.remote.tv.RemoteKey
import online.mmpg.remote.tv.TvDevice
import online.mmpg.remote.tv.TvManager
import online.mmpg.remote.tv.TvPlatform
import org.json.JSONArray
import org.json.JSONObject

class TvBridge(
    context: Context,
    private val webView: WebView,
    private val tvManager: TvManager,
    private val billingManager: BillingManager,
    private val requestNearbyWifiPermission: () -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Set when startDiscovery() had to defer to a runtime permission request;
    // consumed by notifyPermissionResult() to auto-resume the scan once the
    // permission dialog resolves, so the user doesn't have to tap "Procurar" twice.
    @Volatile
    private var discoveryPendingPermission = false

    @JavascriptInterface
    fun getBuildInfo(): String = JSONObject().apply {
        put("mockAllowed", BuildConfig.ALLOW_MOCK)
        put("version", BuildConfig.VERSION_NAME)
    }.toString()

    @JavascriptInterface
    fun startDiscovery() {
        if (!hasNearbyWifiPermission()) {
            Log.i(TAG, "Discovery requested without NEARBY_WIFI_DEVICES; requesting permission first")
            emitDiag("Permissão NEARBY_WIFI_DEVICES ausente; solicitando antes de buscar")
            discoveryPendingPermission = true
            // @JavascriptInterface methods run on the WebView's private background
            // thread, not the UI thread. requestNearbyWifiPermission() ultimately
            // calls shouldShowRequestPermissionRationale()/ActivityResultLauncher.launch()/
            // startActivity() in MainActivity, all of which require the main thread.
            // Route through webView.post {} to hop back onto it, same as emit() does.
            webView.post { requestNearbyWifiPermission() }
            return
        }
        emitDiag("Permissão ok; chamando TvManager.startDiscovery()")
        tvManager.startDiscovery { devices -> publishDevices(devices) }
    }

    @JavascriptInterface
    fun stopDiscovery() = tvManager.stopDiscovery()

    /**
     * NEARBY_WIFI_DEVICES only exists from API 33 (Tiramisu) onward and is the
     * permission that gates NsdManager service discovery on those versions.
     * On API 26-32 no runtime permission is required for NSD/mDNS discovery
     * itself (that requirement historically applies to Wi-Fi scan results and
     * BLE, not to NsdManager) — the manifest-declared network/multicast
     * permissions are install-time and sufficient there.
     */
    private fun hasNearbyWifiPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Steps 1-6 of pairing (see [online.mmpg.remote.tv.providers.AndroidTvProvider.beginPairing]):
     * the TV only displays its PIN after this succeeds, so the UI must call
     * this BEFORE it can know what PIN to ask the user for - it cannot be
     * combined with [submitPairingPin] into a single call.
     */
    @JavascriptInterface
    fun beginPairing(host: String, port: Int) {
        emitDiag("Conectando para parear com $host:$port…")
        scope.launch {
            val result = tvManager.beginPairing(host, port)
            emit("beginPairingResult", result.toString())
        }
    }

    @JavascriptInterface
    fun submitPairingPin(host: String, pin: String) {
        emitDiag("Enviando PIN para $host…")
        scope.launch {
            val result = tvManager.submitPairingCredential(host, pin)
            emit("pairResult", result.toString())
        }
    }

    @JavascriptInterface
    fun cancelPairing() {
        emitDiag("Pareamento cancelado pelo usuário")
        tvManager.cancelPairing()
    }

    @JavascriptInterface
    fun connect(host: String) {
        emitDiag("Conectando ao canal remoto de $host…")
        scope.launch {
            val result = tvManager.connect(host)
            emit("connectResult", result.toString())
        }
    }

    /**
     * [host] identifica qual [online.mmpg.remote.tv.TvProvider] deve receber
     * o comando (ver [TvManager.sendKey]) - antes desta refatoração o app só
     * conhecia um provider e não precisava dessa informação; passou a
     * precisar para que um futuro provider sem conexão persistente (ex.:
     * Roku, que envia cada tecla como uma requisição HTTP separada) funcione
     * com o mesmo contrato.
     */
    @JavascriptInterface
    fun sendKey(host: String, key: String) {
        scope.launch {
            val remoteKey = RemoteKey.fromWireName(key)
            val result = if (remoteKey == null) {
                ProtocolResult(false, "UNKNOWN_KEY", "Comando não suportado: $key")
            } else {
                tvManager.sendKey(host, remoteKey)
            }
            emit("keyResult", result.toString())
        }
    }

    @JavascriptInterface
    fun forget(host: String) {
        scope.launch {
            val result = tvManager.forget(host)
            emit("forgetResult", result.toString())
        }
    }

    /**
     * Resets the app's shared pairing identity (see
     * [TvManager.resetAndroidTvIdentity]). Unlike [forget], this affects
     * every paired TV at once - the UI must only expose it as a separate,
     * clearly-labeled destructive action, never as part of per-TV "Esquecer".
     */
    @JavascriptInterface
    fun resetIdentity() {
        scope.launch {
            val result = tvManager.resetAndroidTvIdentity()
            emit("resetIdentityResult", result.toString())
        }
    }

    /** Lista TVs com credencial salva em qualquer provider, mesmo offline agora; emite "pairedDevices" (mesmo formato de "devices"). */
    @JavascriptInterface
    fun getPairedDevices() {
        scope.launch {
            emit("pairedDevices", devicesToJson(tvManager.pairedDevices()))
        }
    }

    /**
     * Consulta se o usuário já tem premium (vitalício ou assinatura ativa);
     * emite "entitlement". [BillingManager.queryEntitlement] já tem seu
     * próprio timeout contra o BillingClient travar (ex.: DEVELOPER_ERROR
     * com auto-reconexão tentando pra sempre), mas o try/catch aqui é uma
     * segunda camada: `scope.launch` não é aguardado, então qualquer exceção
     * não capturada dentro dele mataria a corrotina em silêncio e o evento
     * "entitlement" nunca chegaria à WebView - e o paywall inteiro depende
     * desse evento pra sequer contar uma sessão (ver paywall.js). Nunca
     * deixar essa checagem sumir sem resposta.
     */
    @JavascriptInterface
    fun getEntitlement() {
        scope.launch {
            val entitlement = try {
                billingManager.queryEntitlement()
            } catch (e: Exception) {
                emitDiag("getEntitlement falhou (${e.javaClass.simpleName}); tratando como não-premium")
                JSONObject().put("premium", false).put("source", JSONObject.NULL)
            }
            emit("entitlement", entitlement.toString())
        }
    }

    /** Busca os preços localizados (o que estiver cadastrado no Play Console); emite "productPrices". Mesma proteção de [getEntitlement]. */
    @JavascriptInterface
    fun getProductPrices() {
        scope.launch {
            val prices = try {
                billingManager.fetchPrices()
            } catch (e: Exception) {
                emitDiag("getProductPrices falhou (${e.javaClass.simpleName})")
                JSONObject()
                    .put("lifetime", JSONObject().put("productId", BillingManager.PRODUCT_LIFETIME).put("price", ""))
                    .put("monthly", JSONObject().put("productId", BillingManager.PRODUCT_MONTHLY).put("price", ""))
            }
            emit("productPrices", prices.toString())
        }
    }

    @JavascriptInterface
    fun buyLifetime() {
        scope.launch {
            emit("buyResult", billingManager.launchPurchase(BillingManager.PRODUCT_LIFETIME).toString())
        }
    }

    @JavascriptInterface
    fun buySubscription() {
        scope.launch {
            emit("buyResult", billingManager.launchPurchase(BillingManager.PRODUCT_MONTHLY).toString())
        }
    }

    /** Chamado por [BillingManager] (via MainActivity) sempre que o entitlement muda. */
    fun emitEntitlement(json: JSONObject) = emit("entitlement", json.toString())

    /** Chamado por [BillingManager] com o desfecho real de uma compra (comprado/cancelado/erro). */
    fun emitPurchaseResult(json: JSONObject) = emit("purchaseResult", json.toString())

    /**
     * Chamado por [MainActivity.onResume] quando o app volta de um período
     * longo em segundo plano (ver [MainActivity]) - minimizar sem fechar de
     * verdade nunca recarrega a WebView, então sem este sinal explícito o
     * paywall (ver `paywall.js`) nunca contaria isso como uma sessão nova.
     */
    fun notifyResumedFromBackground() = emit("resumedFromBackground", JSONObject().toString())

    fun notifyPermissionResult(granted: Boolean) {
        emit("permissionResult", JSONObject().put("granted", granted).toString())
        val wasPending = discoveryPendingPermission
        discoveryPendingPermission = false
        if (granted && wasPending) {
            Log.i(TAG, "NEARBY_WIFI_DEVICES granted; resuming deferred discovery")
            tvManager.startDiscovery { devices -> publishDevices(devices) }
        }
    }

    private fun publishDevices(devices: List<TvDevice>) = emit("devices", devicesToJson(devices))

    private fun devicesToJson(devices: List<TvDevice>): String {
        val arr = JSONArray()
        devices.forEach { d ->
            arr.put(JSONObject().apply {
                put("name", d.name)
                put("host", d.host)
                put("port", d.port ?: JSONObject.NULL)
                put("platform", d.platform.name)
                put("platformLabel", platformLabel(d.platform))
            })
        }
        return arr.toString()
    }

    private fun platformLabel(platform: TvPlatform): String = when (platform) {
        TvPlatform.ANDROID_TV -> "Android TV / Google TV"
        TvPlatform.SAMSUNG_TIZEN -> "Samsung Tizen"
        TvPlatform.LG_WEBOS -> "LG webOS"
        TvPlatform.ROKU -> "Roku"
        TvPlatform.FIRE_TV -> "Fire TV"
        TvPlatform.HISENSE_VIDAA -> "Hisense VIDAA"
        TvPlatform.GENERIC_NETWORK -> "Rede genérica"
        TvPlatform.UNKNOWN -> "Desconhecida"
    }

    /**
     * Forwards a human-readable lifecycle message to the WebView's in-app
     * debug log (visible only in debug builds — see `app.js#initMockIndicator`).
     * This exists so pairing/discovery can be diagnosed on a real device with
     * no ADB/logcat access: every message is also a plain [Log] call, never
     * anything sensitive (no PIN, no key/certificate bytes).
     */
    fun emitDiag(message: String) = emit("diag", message)

    private fun emit(event: String, json: String) {
        val safeEvent = JSONObject.quote(event)
        val payload = JSONObject.quote(json)
        webView.post {
            webView.evaluateJavascript(
                "window.MMPG && window.MMPG.onNative($safeEvent, $payload);",
                null
            )
        }
    }

    fun close() {
        scope.cancel()
        tvManager.close()
    }

    private companion object {
        private const val TAG = "TvBridge"
    }
}
