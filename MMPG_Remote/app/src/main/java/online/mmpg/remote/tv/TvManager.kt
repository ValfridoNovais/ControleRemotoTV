package online.mmpg.remote.tv

import online.mmpg.remote.tv.providers.AndroidTvProvider

/**
 * Único ponto de roteamento entre [online.mmpg.remote.TvBridge] e os
 * [TvProvider]s registrados. Nunca decide o provider pela marca do
 * dispositivo (PROMPT_NOVOS_RECURSO.md, seção 7) - sempre por
 * [TvProvider.ownsHost].
 *
 * Hoje só existe um provider (Android TV). O fallback em [providerFor] para
 * "usa o único provider registrado" existe só para essa fase de um provider
 * só, e se desativa sozinho assim que houver dois ou mais - `singleOrNull()`
 * passa a retornar `null` e o roteamento volta a depender só de
 * [TvProvider.ownsHost], que é o comportamento correto para múltiplos
 * providers.
 */
class TvManager(private val providers: List<TvProvider>) {

    private val devicesById = LinkedHashMap<String, TvDevice>()

    fun startDiscovery(onUpdate: (List<TvDevice>) -> Unit) {
        devicesById.clear()
        providers.forEach { provider ->
            provider.startDiscovery { devices ->
                devices.forEach { devicesById[it.id] = it }
                onUpdate(devicesById.values.sortedBy { it.name.lowercase() })
            }
        }
    }

    fun stopDiscovery() {
        providers.forEach { it.stopDiscovery() }
    }

    private fun providerFor(host: String): TvProvider? =
        providers.firstOrNull { it.ownsHost(host) } ?: providers.singleOrNull()

    suspend fun beginPairing(host: String, port: Int): PairingResult =
        providerFor(host)?.beginPairing(host, port) ?: providerNotFound()

    suspend fun submitPairingCredential(host: String, credential: String): PairingResult =
        providerFor(host)?.submitPairingCredential(host, credential) ?: providerNotFound()

    /** Sem host: só há uma sessão de pareamento em andamento por vez, então cancelar é sempre um broadcast seguro. */
    fun cancelPairing() {
        providers.forEach { it.cancelPairing() }
    }

    suspend fun connect(host: String): ConnectionResult =
        providerFor(host)?.connect(host) ?: providerNotFound()

    suspend fun sendKey(host: String, key: RemoteKey): CommandResult =
        providerFor(host)?.sendKey(host, key) ?: providerNotFound()

    suspend fun forget(host: String): CommandResult =
        providerFor(host)?.forget(host) ?: providerNotFound()

    /** TVs pareadas em todos os providers, mesmo as offline agora - ver [TvProvider.pairedDevices]. */
    suspend fun pairedDevices(): List<TvDevice> =
        providers.flatMap { it.pairedDevices() }.sortedBy { it.name.lowercase() }

    /**
     * Ação hoje específica do Android TV (reseta a identidade TLS
     * compartilhada por TODAS as TVs pareadas de uma vez - ver
     * [AndroidTvProvider.resetIdentity]). Não faz parte do contrato comum
     * [TvProvider] porque nenhum outro provider tem um equivalente ainda;
     * generalizar isso fica para quando um segundo provider precisar de algo
     * parecido.
     */
    suspend fun resetAndroidTvIdentity(): CommandResult {
        val provider = providers.filterIsInstance<AndroidTvProvider>().firstOrNull()
            ?: return providerNotFound()
        return provider.resetIdentity()
    }

    fun close() {
        providers.forEach { provider ->
            provider.stopDiscovery()
            (provider as? java.io.Closeable)?.close()
        }
    }

    private fun providerNotFound(): CommandResult =
        CommandResult(false, "PROVIDER_NOT_FOUND", "Nenhum provider reconhece este dispositivo.")
}
