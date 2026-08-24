package online.mmpg.remote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import online.mmpg.remote.protocol.RemoteProtocol

/**
 * Wraps [NsdManager] to browse for Android TV Remote Protocol v2 services
 * ("_androidtvremote2._tcp") on the LAN.
 *
 * Lifecycle contract:
 * - [start] is idempotent while a scan is already running: it just re-delivers
 *   the current snapshot to the new callback instead of clearing devices and
 *   silently leaving no active listener registered with [NsdManager].
 * - [stop] always unregisters the discovery listener exactly once; it is safe
 *   to call multiple times or when no scan is active.
 * - A device is added/updated on successful resolve and removed on
 *   `onServiceLost`, so a TV that goes offline (or changes network) disappears
 *   from the list instead of lingering until the next full scan.
 *
 * No IP/host is ever hardcoded here: every [TvDevice] comes from mDNS
 * discovery + resolve.
 */
class TvDiscovery(
    context: Context,
    /**
     * Mirrors every [Log] call below to the caller — used to surface a live,
     * in-app diagnostic feed (see [online.mmpg.remote.TvBridge]) for testing
     * on a device with no ADB/logcat access available.
     */
    private val onDiag: (String) -> Unit = {}
) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val registry = TvDeviceRegistry()
    private var callback: ((List<TvDevice>) -> Unit)? = null

    @Volatile
    private var active = false

    private fun logI(msg: String) { Log.i(TAG, msg); onDiag(msg) }
    private fun logD(msg: String) { Log.d(TAG, msg); onDiag(msg) }
    private fun logW(msg: String) { Log.w(TAG, msg); onDiag(msg) }
    private fun logE(msg: String) { Log.e(TAG, msg); onDiag(msg) }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            active = true
            logI("NSD: discovery iniciada para $serviceType")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            if (!service.serviceType.contains(SERVICE_TYPE_FRAGMENT)) {
                logD("NSD: serviço ignorado (tipo diferente): ${service.serviceType}")
                return
            }
            logD("NSD: serviço encontrado \"${service.serviceName}\", resolvendo…")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    logW("NSD: falha ao resolver \"${serviceInfo.serviceName}\" (errorCode=$errorCode)")
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress
                    if (host.isNullOrBlank()) {
                        logW("NSD: \"${resolved.serviceName}\" resolvido sem endereço IP utilizável")
                        return
                    }
                    val device = TvDevice(
                        name = resolved.serviceName.ifBlank { "Android TV" },
                        host = host,
                        port = resolved.port.takeIf { it > 0 } ?: RemoteProtocol.DEFAULT_PAIRING_PORT
                    )
                    logI("NSD: resolvido \"${device.name}\" em ${device.host}:${device.port}")
                    val snapshot = registry.upsert(resolved.serviceName, device)
                    callback?.invoke(snapshot)
                }
            })
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            // NsdServiceInfo on service-lost doesn't reliably carry a resolved
            // host, but the service name is always present and is what devices
            // are keyed by in the registry, so removal still works correctly.
            logI("NSD: serviço perdido \"${service.serviceName}\"")
            val snapshot = registry.remove(service.serviceName)
            callback?.invoke(snapshot)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            active = false
            logI("NSD: discovery parada")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            active = false
            logW("NSD: falha ao iniciar discovery (errorCode=$errorCode)")
            try {
                nsd.stopServiceDiscovery(this)
            } catch (e: Exception) {
                logW("NSD: limpeza após falha de início lançou ${e.javaClass.simpleName}")
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            active = false
            logW("NSD: falha ao parar discovery (errorCode=$errorCode)")
        }
    }

    /**
     * Starts (or re-attaches to) a discovery scan and delivers device list
     * updates to [onUpdate] on every add/remove. If a scan is already active,
     * this only swaps the callback and immediately replays the current
     * snapshot — it does not restart the underlying [NsdManager] scan or wipe
     * already-discovered devices.
     */
    fun start(onUpdate: (List<TvDevice>) -> Unit) {
        callback = onUpdate
        if (active) {
            logD("NSD: start() chamado com scan já ativo; reenviando snapshot atual (${registry.snapshot().size} dispositivo(s))")
            callback?.invoke(registry.snapshot())
            return
        }
        registry.clear()
        logD("NSD: chamando discoverServices($SERVICE_TYPE)")
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: IllegalArgumentException) {
            // Thrown by NsdManager if the listener is already registered; our
            // `active` flag should prevent this, but never crash the bridge on it.
            logE("NSD: discoverServices lançou ${e.javaClass.simpleName}")
        }
    }

    /** Stops the current scan, if any, and unregisters the listener exactly once. */
    fun stop() {
        if (!active) return
        try {
            nsd.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            logW("NSD: stopServiceDiscovery lançou ${e.javaClass.simpleName}")
        } finally {
            active = false
        }
    }

    companion object {
        private const val TAG = "TvDiscovery"
        private const val SERVICE_TYPE = "_androidtvremote2._tcp."
        private const val SERVICE_TYPE_FRAGMENT = "_androidtvremote2._tcp"
    }
}
