package online.mmpg.remote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import online.mmpg.remote.billing.BillingManager
import online.mmpg.remote.discovery.TvDiscovery
import online.mmpg.remote.tv.TvManager
import online.mmpg.remote.tv.providers.AndroidTvProvider
import online.mmpg.remote.tv.providers.LgWebOsProvider
import online.mmpg.remote.tv.providers.SamsungTizenProvider

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var billingManager: BillingManager
    private lateinit var bridge: TvBridge
    private val permissionPrefs by lazy { getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "NEARBY_WIFI_DEVICES result: granted=$granted")
            bridge.notifyPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val discovery = TvDiscovery(this) { msg -> if (::bridge.isInitialized) bridge.emitDiag(msg) }
        val androidTvProvider = AndroidTvProvider(this, discovery) { msg -> if (::bridge.isInitialized) bridge.emitDiag(msg) }
        // LgWebOsProvider ainda não foi validado contra uma TV LG real (ver
        // seu comentário de classe) - registrado mesmo assim porque nenhum
        // ProtocolResult dele finge sucesso; um pareamento/comando que falhar
        // na prática vai reportar um erro honesto, nunca um falso "ok".
        val lgWebOsProvider = LgWebOsProvider(this) { msg -> if (::bridge.isInitialized) bridge.emitDiag(msg) }
        // Idem para Samsung Tizen - ver comentário de classe de SamsungTizenProvider.
        val samsungTizenProvider = SamsungTizenProvider(this) { msg -> if (::bridge.isInitialized) bridge.emitDiag(msg) }
        val tvManager = TvManager(listOf(androidTvProvider, lgWebOsProvider, samsungTizenProvider))
        billingManager = BillingManager(
            activity = this,
            diag = { msg -> if (::bridge.isInitialized) bridge.emitDiag(msg) },
            onEntitlementChanged = { json -> if (::bridge.isInitialized) bridge.emitEntitlement(json) },
            onPurchaseEvent = { json -> if (::bridge.isInitialized) bridge.emitPurchaseResult(json) }
        )
        bridge = TvBridge(this, webView, tvManager, billingManager, ::requestNearbyWifiPermission)
        billingManager.connect()

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.webViewClient = ExternalLinkWebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(bridge, "MMPGNative")
        webView.loadUrl("file:///android_asset/index.html")

        // No permission request here: it's requested lazily, the first time the
        // user actually triggers discovery (see TvBridge.startDiscovery ->
        // requestNearbyWifiPermission below). This avoids prompting before the
        // user has asked for anything and lets a later "Procurar" tap re-prompt
        // after a prior denial, which a fire-and-forget onCreate() request can't do.
    }

    /**
     * Invoked by [TvBridge] when discovery needs NEARBY_WIFI_DEVICES (API 33+)
     * and it isn't currently granted. Handles the three possible states:
     * not yet asked / previously denied (show the system dialog again) vs.
     * permanently denied ("don't ask again" — the system dialog would no
     * longer appear, so we send the user to the app's settings screen instead).
     */
    private fun requestNearbyWifiPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // No such permission below API 33; NSD discovery needs no runtime
            // grant there, so treat it as already satisfied.
            bridge.notifyPermissionResult(true)
            return
        }
        val permission = Manifest.permission.NEARBY_WIFI_DEVICES
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            bridge.notifyPermissionResult(true)
            return
        }
        val askedBefore = permissionPrefs.getBoolean(KEY_ASKED_NEARBY_WIFI, false)
        if (askedBefore && !shouldShowRequestPermissionRationale(permission)) {
            // Denied at least once and the system won't show the dialog again
            // ("don't ask again" / policy) — only the app settings screen can help now.
            Log.i(TAG, "NEARBY_WIFI_DEVICES permanently denied; opening app settings")
            bridge.notifyPermissionResult(false)
            openAppSettings()
            return
        }
        permissionPrefs.edit().putBoolean(KEY_ASKED_NEARBY_WIFI, true).apply()
        permissionLauncher.launch(permission)
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    // Minimizar sem fechar de verdade não recarrega a WebView, então sem
    // isso o paywall (ver paywall.js) nunca contaria um "sai e volta" como
    // sessão nova - só uma abertura genuína do processo. onResume() avisa a
    // WebView quando o app volta depois de BACKGROUND_THRESHOLD_MS parado,
    // pra ela reavaliar o gatilho do paywall como se fosse outra sessão.
    private var pausedAtMillis: Long? = null

    override fun onPause() {
        super.onPause()
        pausedAtMillis = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        val pausedAt = pausedAtMillis
        pausedAtMillis = null
        if (pausedAt != null && System.currentTimeMillis() - pausedAt >= BACKGROUND_THRESHOLD_MS) {
            bridge.notifyResumedFromBackground()
        }
    }

    /**
     * The app UI is a single local asset (`file:///android_asset/index.html`);
     * any `http(s)` navigation (e.g. the "mmpg.online" footer link) is an
     * outbound link, not part of the app, so it's handed to the system
     * browser instead of loading in place — otherwise it would replace the
     * remote UI inside this WebView with no way back.
     */
    private inner class ExternalLinkWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url
            if (url.scheme == "http" || url.scheme == "https") {
                startActivity(Intent(Intent.ACTION_VIEW, url))
                return true
            }
            return false
        }
    }

    override fun onDestroy() {
        // bridge.close() -> tvManager.close() já para a descoberta e fecha o
        // canal remoto de cada provider - ver TvManager.close().
        bridge.close()
        billingManager.close()
        webView.removeJavascriptInterface("MMPGNative")
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_PREFS = "mmpg_permissions"
        private const val KEY_ASKED_NEARBY_WIFI = "asked_nearby_wifi_devices"
        private const val BACKGROUND_THRESHOLD_MS = 5 * 60 * 1000L
    }
}
