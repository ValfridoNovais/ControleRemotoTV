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
import online.mmpg.remote.discovery.TvDevice
import online.mmpg.remote.discovery.TvDiscovery
import online.mmpg.remote.protocol.AndroidTvRemoteService
import org.json.JSONArray
import org.json.JSONObject

class TvBridge(
    context: Context,
    private val webView: WebView,
    private val discovery: TvDiscovery,
    private val requestNearbyWifiPermission: () -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val remoteService = AndroidTvRemoteService(context) { msg -> emitDiag(msg) }

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
        emitDiag("Permissão ok; chamando TvDiscovery.start()")
        discovery.start { devices -> publishDevices(devices) }
    }

    @JavascriptInterface
    fun stopDiscovery() = discovery.stop()

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
     * Steps 1-6 of pairing (see [AndroidTvRemoteService.beginPairing]): the TV
     * only displays its PIN after this succeeds, so the UI must call this
     * BEFORE it can know what PIN to ask the user for - it cannot be combined
     * with [submitPairingPin] into a single call.
     */
    @JavascriptInterface
    fun beginPairing(host: String, port: Int) {
        emitDiag("Conectando para parear com $host:$port…")
        scope.launch {
            val result = remoteService.beginPairing(host, port)
            emit("beginPairingResult", result)
        }
    }

    @JavascriptInterface
    fun submitPairingPin(host: String, pin: String) {
        emitDiag("Enviando PIN para $host…")
        scope.launch {
            val result = remoteService.submitPairingPin(host, pin)
            emit("pairResult", result)
        }
    }

    @JavascriptInterface
    fun cancelPairing() {
        emitDiag("Pareamento cancelado pelo usuário")
        remoteService.cancelPairing()
    }

    @JavascriptInterface
    fun connect(host: String) {
        emitDiag("Conectando ao canal remoto de $host…")
        scope.launch {
            val result = remoteService.connect(host)
            emit("connectResult", result)
        }
    }

    @JavascriptInterface
    fun sendKey(key: String) {
        scope.launch {
            val result = remoteService.sendKey(key)
            emit("keyResult", result)
        }
    }

    @JavascriptInterface
    fun forget(host: String) {
        scope.launch {
            val result = remoteService.forget(host)
            emit("forgetResult", result)
        }
    }

    /**
     * Resets the app's shared pairing identity (see
     * [AndroidTvRemoteService.resetIdentity]). Unlike [forget], this affects
     * every paired TV at once - the UI must only expose it as a separate,
     * clearly-labeled destructive action, never as part of per-TV "Esquecer".
     */
    @JavascriptInterface
    fun resetIdentity() {
        scope.launch {
            val result = remoteService.resetIdentity()
            emit("resetIdentityResult", result)
        }
    }

    fun notifyPermissionResult(granted: Boolean) {
        emit("permissionResult", JSONObject().put("granted", granted).toString())
        val wasPending = discoveryPendingPermission
        discoveryPendingPermission = false
        if (granted && wasPending) {
            Log.i(TAG, "NEARBY_WIFI_DEVICES granted; resuming deferred discovery")
            discovery.start { devices -> publishDevices(devices) }
        }
    }

    private fun publishDevices(devices: List<TvDevice>) {
        val arr = JSONArray()
        devices.forEach { d ->
            arr.put(JSONObject().apply {
                put("name", d.name)
                put("host", d.host)
                put("port", d.port)
            })
        }
        emit("devices", arr.toString())
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
        remoteService.close()
    }

    private companion object {
        private const val TAG = "TvBridge"
    }
}
