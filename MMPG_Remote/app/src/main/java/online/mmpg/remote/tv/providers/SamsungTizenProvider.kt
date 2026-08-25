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
import online.mmpg.remote.tv.providers.samsung.SamsungInfoClient
import online.mmpg.remote.tv.providers.samsung.SamsungKeyStore
import online.mmpg.remote.tv.providers.samsung.SamsungSubnetScan
import online.mmpg.remote.tv.providers.samsung.SamsungWsClient
import online.mmpg.remote.tv.providers.samsung.WakeOnLan
import online.mmpg.remote.tv.providers.ssdp.SsdpDiscovery
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider para TVs Samsung Tizen (2016+) - protocolo WebSocket reverso-
 * projetado que xchwarze/samsung-tv-ws-api e outros clientes open source
 * consolidaram. Ver docs/providers/samsung-tizen.md para as fontes de cada
 * decisão e THIRD_PARTY_NOTICES.md para a atribuição de licença.
 *
 * **Status EXPERIMENTAL até validação num aparelho real** - mesma ressalva
 * do [LgWebOsProvider]: implementado a partir de documentação de terceiros,
 * nenhum `CommandResult` finge sucesso, mas isso não substitui teste real
 * (ver AGENTS.md). Diferente da LG, há uma TV real disponível para validar
 * assim que alguém testar (Samsung UN50RU7100, linha Tizen ~2019).
 *
 * Ao contrário do LG webOS, dá pra descobrir o endereço MAC da TV (via
 * [SamsungInfoClient], um GET não autenticado enquanto ela está ligada) e
 * cacheá-lo para Wake-on-LAN - por isso [TvCapability.POWER] aqui cobre
 * ligar E desligar, não só desligar.
 */
class SamsungTizenProvider(
    context: Context,
    private val onDiag: (String) -> Unit = {}
) : TvProvider, java.io.Closeable {

    override val id: String = PROVIDER_ID
    override val name: String = "Samsung Tizen"
    override val platform: TvPlatform = TvPlatform.SAMSUNG_TIZEN
    override val pairingMethod: PairingMethod = PairingMethod.TV_CONFIRMATION

    private val keyStore = SamsungKeyStore(context)
    private val discovery = SsdpDiscovery(context, onDiag)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val knownHosts = ConcurrentHashMap.newKeySet<String>()
    private val clients = ConcurrentHashMap<String, SamsungWsClient>()

    // Último nome visto por host na descoberta - só para salvar um nome
    // legível no SamsungKeyStore quando o pareamento é concluído.
    private val lastKnownNames = ConcurrentHashMap<String, String>()

    override fun supports(capability: TvCapability): Boolean = capability in CAPABILITIES

    override fun ownsHost(host: String): Boolean = host in knownHosts || keyStore.getToken(host) != null

    /**
     * Roda duas estratégias em paralelo, não só SSDP: o SSDP de TVs Samsung
     * é conhecidamente pouco confiável em vários modelos/redes (achado ao
     * testar contra uma UN50RU7100G real - ver docs/providers/samsung-tizen.md
     * e THIRD_PARTY_NOTICES.md, com fontes documentando o mesmo problema em
     * outros projetos). [SamsungSubnetScan] sonda `:8001/api/v2/` direto em
     * cada IP da sub-rede como alternativa que não depende do anúncio SSDP
     * funcionar. Os dois mecanismos escrevem no mesmo mapa (thread-safe:
     * podem chegar de threads diferentes ao mesmo tempo) e disparam
     * [onUpdate] incrementalmente, então uma TV pode aparecer na lista pelo
     * caminho que responder primeiro.
     */
    override fun startDiscovery(onUpdate: (List<TvDevice>) -> Unit) {
        val found = ConcurrentHashMap<String, TvDevice>()
        knownHosts.clear()

        fun publish(host: String, info: SamsungInfoClient.Info?, friendlyName: String?) {
            knownHosts.add(host)
            when {
                info?.wifiMac != null -> {
                    keyStore.saveMac(host, info.wifiMac)
                    onDiag("Samsung: MAC de $host capturado (${info.wifiMac}) — Wake-on-LAN disponível pra ligar essa TV")
                }
                info != null -> onDiag("Samsung: $host respondeu ao GET de info, mas sem campo wifiMac — este firmware não vai permitir ligar via Wake-on-LAN")
                else -> {} // achado só via SSDP/varredura sem resposta HTTP; sem novidade sobre o MAC.
            }
            val resolvedName = info?.name ?: friendlyName
            if (resolvedName != null) lastKnownNames[host] = resolvedName
            found[host] = TvDevice(
                id = "$PROVIDER_ID:$host",
                name = resolvedName ?: "Samsung TV",
                host = host,
                port = null,
                manufacturer = "Samsung",
                model = info?.modelName,
                platform = platform,
                providerId = PROVIDER_ID,
                services = SERVICES,
                capabilities = CAPABILITIES
            )
            onUpdate(found.values.sortedBy { it.name.lowercase() })
        }

        discovery.search(scope, SEARCH_TARGET) { ssdpDevice ->
            // Busca de nome/modelo/MAC em paralelo, sem travar o loop de
            // recebimento SSDP (que continua ouvindo outras respostas
            // enquanto este GET de metadados roda).
            scope.launch {
                publish(ssdpDevice.host, SamsungInfoClient.fetch(ssdpDevice.host), ssdpDevice.friendlyName)
            }
        }

        scope.launch {
            onDiag("Samsung: varrendo a sub-rede local (o SSDP sozinho não é confiável para TVs Samsung)")
            SamsungSubnetScan.scan { host, info -> publish(host, info, null) }
        }
    }

    override fun stopDiscovery() = discovery.stop()

    /**
     * Pareamento por confirmação na TV, igual à LG (ver
     * [LgWebOsProvider.beginPairing]): uma única conexão WebSocket já basta -
     * a TV só responde depois que o usuário aceita (ou rejeita) o prompt na
     * tela, ou nunca, se ignorar. Sem PIN; [submitPairingCredential] nunca é
     * usado de fato.
     */
    override suspend fun beginPairing(host: String, port: Int): PairingResult {
        val client = clientFor(host)
        return when (val outcome = client.connect(host, keyStore.getToken(host), PAIR_TIMEOUT_MS)) {
            is SamsungWsClient.ConnectOutcome.Paired -> {
                outcome.token?.let { keyStore.saveToken(host, it) }
                lastKnownNames[host]?.let { keyStore.saveName(host, it) }
                PairingResult(true, "PAIRED", "TV pareada com sucesso.")
            }
            SamsungWsClient.ConnectOutcome.Rejected ->
                PairingResult(false, "PAIRING_REJECTED", "A TV recusou o pareamento.")
            SamsungWsClient.ConnectOutcome.Timeout ->
                PairingResult(false, "TIMEOUT", "A TV não respondeu à solicitação de pareamento a tempo.")
            SamsungWsClient.ConnectOutcome.ConnectionFailed -> PairingResult(false, "CONNECTION_ERROR", CONNECTION_FAILED_MESSAGE)
        }
    }

    override suspend fun submitPairingCredential(host: String, credential: String): PairingResult =
        PairingResult(false, "NOT_APPLICABLE", "Este provider confirma o pareamento direto na TV, sem PIN.")

    override fun cancelPairing() {
        clients.values.forEach { it.close() }
    }

    override suspend fun connect(host: String): ConnectionResult {
        val savedToken = keyStore.getToken(host)
            ?: return ConnectionResult(false, "NOT_PAIRED", "Pareie esta TV antes de conectar.")
        val client = clientFor(host)
        return when (val outcome = client.connect(host, savedToken, RECONNECT_TIMEOUT_MS)) {
            is SamsungWsClient.ConnectOutcome.Paired -> {
                outcome.token?.let { keyStore.saveToken(host, it) }
                ConnectionResult(true, "CONNECTED", "Canal remoto conectado.")
            }
            SamsungWsClient.ConnectOutcome.Rejected ->
                ConnectionResult(false, "NOT_PAIRED", "A TV não reconheceu este token - pareie novamente.")
            SamsungWsClient.ConnectOutcome.Timeout ->
                ConnectionResult(false, "TIMEOUT", "A TV não respondeu a tempo.")
            SamsungWsClient.ConnectOutcome.ConnectionFailed -> ConnectionResult(false, "CONNECTION_ERROR", CONNECTION_FAILED_MESSAGE)
        }
    }

    override suspend fun sendKey(host: String, key: RemoteKey): CommandResult {
        if (key == RemoteKey.POWER && clients[host] == null) {
            // Sem canal aberto: a TV provavelmente está desligada - a única
            // forma de "ligar" é Wake-on-LAN com o MAC cacheado da última
            // vez que ela respondeu ao GET de info (ver startDiscovery).
            val mac = keyStore.getMac(host)
                ?: run {
                    onDiag("Samsung: tentativa de ligar $host sem MAC cacheado — nunca capturado nesta TV")
                    return CommandResult(
                        false, "NOT_IMPLEMENTED",
                        "Endereço MAC desconhecido - conecte-se a esta TV pelo menos uma vez enquanto ligada antes de tentar ligá-la remotamente."
                    )
                }
            onDiag("Samsung: ligando $host via Wake-on-LAN (MAC $mac)")
            val sent = WakeOnLan.send(mac, onDiag)
            return if (sent) {
                CommandResult(true, "KEY_SENT", "Pacote de ligar (Wake-on-LAN) enviado.")
            } else {
                CommandResult(false, "CONNECTION_ERROR", "Não foi possível enviar o pacote Wake-on-LAN.")
            }
        }

        val client = clients[host]
            ?: return CommandResult(false, "NOT_CONNECTED", "Conecte-se à TV antes de enviar comandos.")
        val keyCode = KEY_CODES[key]
            ?: return CommandResult(false, "NOT_IMPLEMENTED", "Comando '${key.wireName}' ainda não implementado neste provider.")
        return if (client.pressKey(keyCode)) {
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
            name = keyStore.getName(host) ?: "Samsung TV ($host)",
            host = host,
            port = null,
            manufacturer = "Samsung",
            model = null,
            platform = platform,
            providerId = PROVIDER_ID,
            services = SERVICES,
            capabilities = CAPABILITIES
        )
    }

    private fun clientFor(host: String): SamsungWsClient =
        clients.getOrPut(host) { SamsungWsClient(APP_NAME, onDiag) }

    override fun close() {
        discovery.stop()
        clients.values.forEach { it.close() }
        clients.clear()
    }

    private companion object {
        const val PROVIDER_ID = "samsung_tizen"
        const val APP_NAME = "MMPG Remote"
        const val SEARCH_TARGET = "urn:samsung.com:device:RemoteControlReceiver:1"
        const val PAIR_TIMEOUT_MS = 30_000L
        const val RECONNECT_TIMEOUT_MS = 15_000L

        // SamsungWsClient já tenta :8002 (TLS) e cai para :8001 (sem TLS,
        // modelos 2016-2018) sozinho - se as duas falharem, o motivo mais
        // provável documentado pela comunidade é um modelo Orsay anterior a
        // 2016 (linha H ou mais antiga), que usa um protocolo totalmente
        // diferente (Encrypted API v1, XML sobre HTTP) - ver
        // docs/providers/samsung-tizen.md.
        const val CONNECTION_FAILED_MESSAGE = "Não foi possível conectar à TV. Se ela for anterior a 2016 " +
            "(linha H ou mais antiga), pode usar um protocolo diferente, não suportado por este provider."

        val SERVICES = listOf(SEARCH_TARGET)

        val CAPABILITIES = setOf(
            TvCapability.POWER, TvCapability.DPAD, TvCapability.HOME, TvCapability.BACK,
            TvCapability.VOLUME, TvCapability.MUTE, TvCapability.CHANNEL
        )

        // PLAY_PAUSE fica de fora pela mesma razão do LgWebOsProvider: KEY_PLAY
        // e KEY_PAUSE existem separados, sem alternador único confirmado.
        val KEY_CODES: Map<RemoteKey, String> = mapOf(
            RemoteKey.UP to "KEY_UP",
            RemoteKey.DOWN to "KEY_DOWN",
            RemoteKey.LEFT to "KEY_LEFT",
            RemoteKey.RIGHT to "KEY_RIGHT",
            RemoteKey.ENTER to "KEY_ENTER",
            RemoteKey.HOME to "KEY_HOME",
            RemoteKey.BACK to "KEY_RETURN",
            RemoteKey.POWER to "KEY_POWER",
            RemoteKey.VOLUME_UP to "KEY_VOLUP",
            RemoteKey.VOLUME_DOWN to "KEY_VOLDOWN",
            RemoteKey.MUTE to "KEY_MUTE",
            RemoteKey.CHANNEL_UP to "KEY_CHUP",
            RemoteKey.CHANNEL_DOWN to "KEY_CHDOWN"
        )
    }
}
