package online.mmpg.remote.tv

/**
 * Contrato comum que cada fabricante/protocolo de TV implementa. Adaptado a
 * partir do esboço em PROMPT_NOVOS_RECURSO.md com três desvios deliberados,
 * para se encaixar na implementação Android TV já validada contra hardware
 * real (TCL 32S615) sem reescrevê-la:
 *
 *  1. `discover(): List<TvDevice>` virou [startDiscovery]/[stopDiscovery]: a
 *     descoberta NSD/mDNS existente é orientada a eventos
 *     (onServiceFound/onServiceLost ao longo do tempo via `NsdManager`), não
 *     uma lista única resolvida de uma vez só.
 *  2. Os métodos recebem `host: String` em vez de um [TvDevice] inteiro: todo
 *     o código já existente (`CertificateStore`, `TvBridge`, a UI web) já
 *     indexa tudo por host; exigir o objeto completo obrigaria a mudar o
 *     contrato JS↔Kotlin sem necessidade nesta fase.
 *  3. `pair(device, credential)` virou [beginPairing]/[submitPairingCredential]/
 *     [cancelPairing]: o pareamento por PIN do Android TV é assíncrono em
 *     duas etapas (a TV só mostra o PIN depois que o handshake começa) - um
 *     único método `pair()` não descreve esse fluxo real.
 */
interface TvProvider {
    val id: String
    val name: String
    val platform: TvPlatform
    val pairingMethod: PairingMethod

    /** Inicia (ou reaproveita) uma varredura contínua; [onUpdate] é chamado a cada mudança na lista. */
    fun startDiscovery(onUpdate: (List<TvDevice>) -> Unit)
    fun stopDiscovery()

    /** Primeira metade do pareamento: conecta e negocia até a TV exibir o código/solicitar confirmação. */
    suspend fun beginPairing(host: String, port: Int): PairingResult

    /** Segunda metade: envia o PIN/token/confirmação do usuário e conclui o pareamento. */
    suspend fun submitPairingCredential(host: String, credential: String): PairingResult

    /** Cancela um pareamento em andamento aberto por [beginPairing], sem completá-lo. */
    fun cancelPairing()

    suspend fun connect(host: String): ConnectionResult
    suspend fun sendKey(host: String, key: RemoteKey): CommandResult
    suspend fun launchApp(host: String, appId: String): CommandResult
    suspend fun disconnect(host: String)
    suspend fun forget(host: String): CommandResult

    /**
     * TVs com credencial de pareamento salva neste provider, mesmo que não
     * estejam visíveis na descoberta agora (desligadas, fora de alcance) -
     * usado pela tela "Minhas TVs" da UI. Distinto de [startDiscovery]: isso
     * lê só o que já está persistido, não sonda a rede.
     */
    suspend fun pairedDevices(): List<TvDevice>

    /** Este host já foi descoberto ou já está pareado por este provider? Usado por [TvManager] para rotear. */
    fun ownsHost(host: String): Boolean

    fun supports(capability: TvCapability): Boolean
}
