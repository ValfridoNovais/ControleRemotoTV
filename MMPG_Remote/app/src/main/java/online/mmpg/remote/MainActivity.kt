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
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import online.mmpg.remote.discovery.TvDiscovery

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var discovery: TvDiscovery
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

        discovery = TvDiscovery(this)
        bridge = TvBridge(this, webView, discovery, ::requestNearbyWifiPermission)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.webViewClient = WebViewClient()
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

    override fun onDestroy() {
        discovery.stop()
        bridge.close()
        webView.removeJavascriptInterface("MMPGNative")
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_PREFS = "mmpg_permissions"
        private const val KEY_ASKED_NEARBY_WIFI = "asked_nearby_wifi_devices"
    }
}
