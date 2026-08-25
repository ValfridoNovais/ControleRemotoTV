package online.mmpg.remote.tv.providers

import android.content.Context
import online.mmpg.remote.discovery.TvDiscovery
import online.mmpg.remote.protocol.CertificateStore
import online.mmpg.remote.protocol.PairingClient
import online.mmpg.remote.protocol.RemoteClient
import online.mmpg.remote.tv.CommandResult
import online.mmpg.remote.tv.ConnectionResult
import online.mmpg.remote.tv.PairingMethod
import online.mmpg.remote.tv.PairingResult
import online.mmpg.remote.tv.RemoteKey
import online.mmpg.remote.tv.TvCapability
import online.mmpg.remote.tv.TvDevice
import online.mmpg.remote.tv.TvPlatform
import online.mmpg.remote.tv.TvProvider
import java.util.concurrent.ConcurrentHashMap
import online.mmpg.remote.discovery.TvDevice as NsdTvDevice

/**
 * Adapta o Android TV Remote Protocol v2 já validado contra uma TV real
 * (TCL 32S615) ao contrato [TvProvider]. É a promoção direta do antigo
 * `protocol/AndroidTvRemoteService.kt` (mesma orquestração de
 * [CertificateStore]/[PairingClient]/[RemoteClient], mesmos métodos) - esta
 * refatoração não mudou uma linha sequer dessas três classes nem de
 * [TvDiscovery], que é apenas reutilizada aqui, não reescrita.
 */
class AndroidTvProvider(
    context: Context,
    private val discovery: TvDiscovery,
    /** Espelha mensagens de diagnóstico; ver [online.mmpg.remote.TvBridge]. */
    onDiag: (String) -> Unit = {}
) : TvProvider, java.io.Closeable {

    override val id: String = PROVIDER_ID
    override val name: String = "Android TV / Google TV"
    override val platform: TvPlatform = TvPlatform.ANDROID_TV
    override val pairingMethod: PairingMethod = PairingMethod.PIN

    private val store = CertificateStore(context, onDiag)
    private val pairingClient = PairingClient(store, onDiag)
    private val remoteClient = RemoteClient(store, onDiag)

    // Hosts vistos na última varredura - junto com CertificateStore.isPaired,
    // é o que TvManager usa para decidir se um host pertence a este provider.
    private val knownHosts = ConcurrentHashMap.newKeySet<String>()

    // Último nome visto por host na descoberta - usado só para salvar um
    // nome legível em CertificateStore quando o pareamento é concluído (ver
    // submitPairingCredential); o protocolo em si não depende disso.
    private val lastKnownNames = ConcurrentHashMap<String, String>()

    override fun supports(capability: TvCapability): Boolean = capability in CAPABILITIES

    override fun ownsHost(host: String): Boolean = host in knownHosts || store.isPaired(host)

    override fun startDiscovery(onUpdate: (List<TvDevice>) -> Unit) {
        discovery.start { devices ->
            knownHosts.clear()
            devices.forEach { knownHosts.add(it.host); lastKnownNames[it.host] = it.name }
            onUpdate(devices.map(::toProviderDevice))
        }
    }

    override fun stopDiscovery() = discovery.stop()

    private fun toProviderDevice(d: NsdTvDevice): TvDevice = TvDevice(
        id = "$PROVIDER_ID:${d.name}",
        name = d.name,
        host = d.host,
        port = d.port,
        manufacturer = null,
        model = null,
        platform = platform,
        providerId = PROVIDER_ID,
        services = SERVICES,
        capabilities = CAPABILITIES
    )

    /** Passos 1-6 do pareamento: conecta e negocia até a TV exibir o PIN. */
    override suspend fun beginPairing(host: String, port: Int): PairingResult =
        pairingClient.beginPairing(host, port)

    /** Passos 7-8: envia o segredo derivado do PIN e conclui o pareamento. */
    override suspend fun submitPairingCredential(host: String, credential: String): PairingResult {
        val result = pairingClient.submitPin(credential)
        if (result.ok) {
            store.markPaired(host, true)
            lastKnownNames[host]?.let { store.saveName(host, it) }
        }
        return result
    }

    override fun cancelPairing() = pairingClient.cancelPairing()

    override suspend fun connect(host: String): ConnectionResult {
        if (!store.isPaired(host)) {
            return ConnectionResult(false, "NOT_PAIRED", "Pareie esta TV antes de conectar.")
        }
        return remoteClient.connect(host)
    }

    override suspend fun sendKey(host: String, key: RemoteKey): CommandResult =
        remoteClient.sendKey(key.wireName)

    override suspend fun launchApp(host: String, appId: String): CommandResult =
        CommandResult(false, "NOT_IMPLEMENTED", "Abrir apps ainda não é suportado neste provider.")

    override suspend fun disconnect(host: String) = remoteClient.close()

    override suspend fun forget(host: String): CommandResult {
        remoteClient.close()
        store.forget(host)
        return CommandResult(true, "FORGOTTEN", "TV removida deste aparelho.")
    }

    override suspend fun pairedDevices(): List<TvDevice> = store.pairedHosts().map { host ->
        TvDevice(
            id = "$PROVIDER_ID:$host",
            name = store.getName(host) ?: host,
            host = host,
            port = null,
            manufacturer = null,
            model = null,
            platform = platform,
            providerId = PROVIDER_ID,
            services = SERVICES,
            capabilities = CAPABILITIES
        )
    }

    /**
     * Ação específica deste provider (fora do contrato comum [TvProvider]):
     * reseta a identidade TLS compartilhada por TODAS as TVs pareadas de uma
     * vez - ver [CertificateStore.resetClientIdentity]. Chamada só pela ação
     * "Redefinir identidade do app" da UI, nunca por "Esquecer" de uma TV
     * só. Ver [online.mmpg.remote.tv.TvManager.resetAndroidTvIdentity].
     */
    suspend fun resetIdentity(): CommandResult {
        remoteClient.close()
        store.resetClientIdentity()
        return CommandResult(
            true, "IDENTITY_RESET", "Identidade do app redefinida. Todas as TVs precisam ser pareadas novamente."
        )
    }

    override fun close() {
        pairingClient.cancelPairing()
        remoteClient.close()
    }

    private companion object {
        const val PROVIDER_ID = "android_tv"
        val SERVICES = listOf("_androidtvremote2._tcp")
        val CAPABILITIES = setOf(
            TvCapability.POWER, TvCapability.DPAD, TvCapability.HOME, TvCapability.BACK,
            TvCapability.VOLUME, TvCapability.MUTE, TvCapability.CHANNEL, TvCapability.PLAYBACK
        )
    }
}
