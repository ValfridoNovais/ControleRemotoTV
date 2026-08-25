package online.mmpg.remote.tv.providers

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import online.mmpg.remote.tv.CommandResult
import online.mmpg.remote.tv.ConnectionResult
import online.mmpg.remote.tv.PairingMethod
import online.mmpg.remote.tv.PairingResult
import online.mmpg.remote.tv.RemoteKey
import online.mmpg.remote.tv.TvCapability
import online.mmpg.remote.tv.TvDevice
import online.mmpg.remote.tv.TvPlatform
import online.mmpg.remote.tv.TvProvider
import online.mmpg.remote.tv.providers.lgwebos.LgKeyStore
import online.mmpg.remote.tv.providers.lgwebos.LgManifest
import online.mmpg.remote.tv.providers.lgwebos.LgSsapClient
import online.mmpg.remote.tv.providers.lgwebos.LgSubnetScan
import online.mmpg.remote.tv.providers.ssdp.SsdpDiscovery
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider para TVs LG webOS - protocolo WebSocket/SSAP reverso-projetado
 * que hobbyquaker/lgtv2 (MIT) e outros clientes open source consolidaram há
 * anos. Ver docs/providers/lg-webos.md para as fontes de cada decisão e
 * THIRD_PARTY_NOTICES.md para a atribuição de licença.
 *
 * **NÃO VALIDADO CONTRA HARDWARE REAL.** Ao contrário do [AndroidTvProvider]
 * (confirmado ponta a ponta contra uma TCL 32S615 física), este provider foi
 * implementado a partir de documentação/implementações de terceiros, sem
 * nenhuma TV LG disponível para testar de fato. Ele nunca finge sucesso -
 * todo [online.mmpg.remote.tv.CommandResult] só reporta `ok=true` depois de
 * uma resposta real da TV no socket - mas "compila e parece certo" não é o
 * mesmo que "funciona" (ver AGENTS.md: "Não invente sucesso de pareamento").
 * Status: EXPERIMENTAL até validação num aparelho real.
 *
 * Limitação conhecida: ligar uma TV desligada exigiria Wake-on-LAN com o
 * endereço MAC do aparelho, que este provider não descobre de forma
 * confiável a partir da resposta SSDP no Android (sem acesso à tabela ARP) -
 * por isso [TvCapability.POWER] aqui só cobre desligar; a TV precisa já
 * estar ligada e conectada. Ver docs/providers/lg-webos.md.
 */
class LgWebOsProvider(
    context: Context,
    private val onDiag: (String) -> Unit = {}
) : TvProvider, java.io.Closeable {

    override val id: String = PROVIDER_ID
    override val name: String = "LG webOS"
    override val platform: TvPlatform = TvPlatform.LG_WEBOS
    override val pairingMethod: PairingMethod = PairingMethod.TV_CONFIRMATION

    private val keyStore = LgKeyStore(context)
    private val discovery = SsdpDiscovery(context, onDiag)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val knownHosts = ConcurrentHashMap.newKeySet<String>()
    private val clients = ConcurrentHashMap<String, LgSsapClient>()

    // Último nome visto por host na descoberta - só para salvar um nome
    // legível no LgKeyStore quando o pareamento é concluído.
    private val lastKnownNames = ConcurrentHashMap<String, String>()

    override fun supports(capability: TvCapability): Boolean = capability in CAPABILITIES

    override fun ownsHost(host: String): Boolean = host in knownHosts || keyStore.get(host) != null

    /**
     * Roda SSDP e uma varredura de sub-rede em paralelo, não só SSDP: o SSDP
     * de TVs LG também não é confiável em vários modelos/redes - mesmo
     * problema documentado para Samsung, mais o próprio consenso da
     * comunidade lgtv2 (ver docs/providers/lg-webos.md). [LgSubnetScan]
     * sonda a porta WebSocket direto em cada IP da sub-rede, sem nunca
     * mandar "register" (então não dispara o prompt de confirmação em TVs
     * que o usuário nem selecionou). Os dois mecanismos escrevem no mesmo
     * mapa (thread-safe) e disparam [onUpdate] incrementalmente.
     */
    override fun startDiscovery(onUpdate: (List<TvDevice>) -> Unit) {
        val found = ConcurrentHashMap<String, TvDevice>()
        knownHosts.clear()

        fun publish(host: String, friendlyName: String?) {
            knownHosts.add(host)
            // Só grava um nome vindo de verdade do SSDP (LOCATION/friendlyName);
            // a sondagem de sub-rede não tem como saber o nome, e não deve
            // sobrescrever um nome melhor já visto com o rótulo genérico "LG TV".
            if (friendlyName != null) lastKnownNames[host] = friendlyName
            found[host] = TvDevice(
                id = "$PROVIDER_ID:$host",
                name = friendlyName ?: "LG TV",
                host = host,
                port = null,
                manufacturer = "LG",
                model = null,
                platform = platform,
                providerId = PROVIDER_ID,
                services = SERVICES,
                capabilities = CAPABILITIES
            )
            onUpdate(found.values.sortedBy { it.name.lowercase() })
        }

        discovery.search(scope, SEARCH_TARGET) { device -> publish(device.host, device.friendlyName) }

        scope.launch {
            onDiag("LG webOS: varrendo a sub-rede local (o SSDP sozinho não é confiável para TVs LG)")
            LgSubnetScan.scan(onDiag = onDiag) { host -> publish(host, null) }
        }
    }

    override fun stopDiscovery() = discovery.stop()

    /**
     * Pareamento por confirmação na TV: uma única troca "register" já basta
     * (a TV só responde depois que o usuário aceita o prompt no controle
     * dela, ou nunca, se ignorar) - não existe um PIN para o usuário digitar
     * no celular, então este método sozinho já conclui (ou falha) o
     * pareamento inteiro; [submitPairingCredential] nunca é usado de fato.
     */
    override suspend fun beginPairing(host: String, port: Int): PairingResult {
        val client = clientFor(host)
        if (!client.connect(host)) {
            return PairingResult(false, "CONNECTION_ERROR", CONNECTION_FAILED_MESSAGE)
        }

        val manifest = JSONObject(LgManifest.JSON)
        keyStore.get(host)?.let { manifest.put("client-key", it) }

        onDiag("LG webOS: registrando com $host — confirme na tela da TV se solicitado")
        val response = client.register(manifest, timeoutMs = REGISTER_TIMEOUT_MS)
            ?: return PairingResult(false, "TIMEOUT", PAIRING_TIMEOUT_MESSAGE)

        if (response.optString("type") != "registered") {
            return PairingResult(false, "PAIRING_REJECTED", "A TV recusou o pareamento.")
        }
        val clientKey = response.optJSONObject("payload")?.optString("client-key")
        if (clientKey.isNullOrBlank()) {
            return PairingResult(false, "PROTOCOL_ERROR", "A TV confirmou o pareamento sem devolver uma chave.")
        }
        keyStore.save(host, clientKey)
        lastKnownNames[host]?.let { keyStore.saveName(host, it) }
        return PairingResult(true, "PAIRED", "TV pareada com sucesso.")
    }

    override suspend fun submitPairingCredential(host: String, credential: String): PairingResult =
        PairingResult(false, "NOT_APPLICABLE", "Este provider confirma o pareamento direto na TV, sem PIN.")

    override fun cancelPairing() {
        clients.values.forEach { it.close() }
    }

    override suspend fun connect(host: String): ConnectionResult {
        val savedKey = keyStore.get(host)
            ?: return ConnectionResult(false, "NOT_PAIRED", "Pareie esta TV antes de conectar.")
        val client = clientFor(host)
        if (!client.connect(host)) {
            return ConnectionResult(false, "CONNECTION_ERROR", CONNECTION_FAILED_MESSAGE)
        }

        val manifest = JSONObject(LgManifest.JSON).put("client-key", savedKey)
        val response = client.register(manifest, timeoutMs = RECONNECT_TIMEOUT_MS)
            ?: return ConnectionResult(false, "TIMEOUT", "A TV não respondeu a tempo.")
        if (response.optString("type") != "registered") {
            return ConnectionResult(false, "NOT_PAIRED", "A TV não reconheceu esta chave - pareie novamente.")
        }
        return ConnectionResult(true, "CONNECTED", "Canal remoto conectado.")
    }

    override suspend fun sendKey(host: String, key: RemoteKey): CommandResult {
        val client = clients[host]
            ?: return CommandResult(false, "NOT_CONNECTED", "Conecte-se à TV antes de enviar comandos.")

        if (key == RemoteKey.POWER) {
            val response = client.request("ssap://system/turnOff")
            val ok = response?.optJSONObject("payload")?.optBoolean("returnValue", false) == true
            return if (ok) {
                CommandResult(true, "KEY_SENT", "TV desligada.")
            } else {
                CommandResult(
                    false, "NOT_IMPLEMENTED",
                    "Não foi possível desligar a TV. Ligar uma TV já desligada via Wake-on-LAN ainda não é suportado neste provider."
                )
            }
        }

        val buttonName = BUTTON_NAMES[key]
            ?: return CommandResult(false, "NOT_IMPLEMENTED", "Comando '${key.wireName}' ainda não implementado neste provider.")
        return if (client.pressButton(buttonName)) {
            CommandResult(true, "KEY_SENT", "Comando enviado.")
        } else {
            CommandResult(false, "CONNECTION_ERROR", "Conexão com a TV perdida ao enviar o comando.")
        }
    }

    override suspend fun launchApp(host: String, appId: String): CommandResult =
        CommandResult(false, "NOT_IMPLEMENTED", "Abrir apps ainda não é suportado neste provider.")

    override suspend fun disconnect(host: String) {
        clients.remove(host)?.close()
    }

    override suspend fun forget(host: String): CommandResult {
        clients.remove(host)?.close()
        keyStore.forget(host)
        return CommandResult(true, "FORGOTTEN", "TV removida deste aparelho.")
    }

    override suspend fun pairedDevices(): List<TvDevice> = keyStore.pairedHosts().map { host ->
        TvDevice(
            id = "$PROVIDER_ID:$host",
            name = keyStore.getName(host) ?: "LG TV ($host)",
            host = host,
            port = null,
            manufacturer = "LG",
            model = null,
            platform = platform,
            providerId = PROVIDER_ID,
            services = SERVICES,
            capabilities = CAPABILITIES
        )
    }

    private fun clientFor(host: String): LgSsapClient = clients.getOrPut(host) { LgSsapClient(onDiag) }

    override fun close() {
        discovery.stop()
        clients.values.forEach { it.close() }
        clients.clear()
    }

    private companion object {
        const val PROVIDER_ID = "lg_webos"
        const val REGISTER_TIMEOUT_MS = 60_000L
        const val RECONNECT_TIMEOUT_MS = 15_000L
        const val SEARCH_TARGET = "urn:lge-com:service:webos-second-screen:1"

        // Motivos documentados pela comunidade (issues do hobbyquaker/lgtv2 e
        // da integração LG webOS do Home Assistant) para "não conecta"/"TV
        // nunca responde", nenhum dos dois um bug deste app - ver
        // docs/providers/lg-webos.md.
        const val CONNECTION_FAILED_MESSAGE = "Não foi possível conectar à TV. Confira se \"LG Connect Apps\"/" +
            "\"Controle de IP em rede\" está habilitado nas configurações da TV. Se ela for anterior a ~2014 " +
            "(NetCast, não webOS), usa um protocolo diferente, não suportado por este provider."
        const val PAIRING_TIMEOUT_MESSAGE = "A TV não respondeu à solicitação de pareamento a tempo. " +
            "Confira se \"LG Connect Apps\"/\"Controle de IP em rede\" está habilitado nas configurações da TV."

        val SERVICES = listOf(SEARCH_TARGET)

        val CAPABILITIES = setOf(
            TvCapability.POWER, TvCapability.DPAD, TvCapability.HOME, TvCapability.BACK,
            TvCapability.VOLUME, TvCapability.MUTE, TvCapability.CHANNEL
        )

        // PLAY_PAUSE fica de fora de propósito: webOS tem botões PLAY e PAUSE
        // separados, não um único alternador - mapear PLAY_PAUSE para
        // qualquer um dos dois adivinharia o estado da TV; ver sendKey/NOT_IMPLEMENTED.
        val BUTTON_NAMES: Map<RemoteKey, String> = mapOf(
            RemoteKey.UP to "UP",
            RemoteKey.DOWN to "DOWN",
            RemoteKey.LEFT to "LEFT",
            RemoteKey.RIGHT to "RIGHT",
            RemoteKey.ENTER to "ENTER",
            RemoteKey.HOME to "HOME",
            RemoteKey.BACK to "BACK",
            RemoteKey.VOLUME_UP to "VOLUMEUP",
            RemoteKey.VOLUME_DOWN to "VOLUMEDOWN",
            RemoteKey.MUTE to "MUTE",
            RemoteKey.CHANNEL_UP to "CHANNELUP",
            RemoteKey.CHANNEL_DOWN to "CHANNELDOWN"
        )
    }
}
