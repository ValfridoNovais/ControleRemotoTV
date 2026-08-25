package online.mmpg.remote.tv.providers.lgwebos

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Transporte WebSocket/SSAP para um LG webOS TV - o equivalente, para este
 * provider, ao par PairingClient/RemoteClient do Android TV: sabe abrir o
 * socket, fazer o "register" (pareamento) e enviar requisições `ssap://`,
 * mas não decide nada sobre RemoteKey/TvDevice (isso é [LgWebOsProvider]).
 *
 * Formato das mensagens (`{id, type, uri?, payload}`), estratégia de porta
 * (tenta `wss://host:3001` primeiro, cai para `ws://host:3000` em TVs mais
 * antigas) e o protocolo de texto do socket de ponteiro/botão
 * (`type:button\nname:X\n\n`) vêm de hobbyquaker/lgtv2 (MIT) - ver
 * docs/providers/lg-webos.md e THIRD_PARTY_NOTICES.md.
 */
class LgSsapClient(
    private val onDiag: (String) -> Unit = {}
) {
    private var mainSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null

    // Requisições ssap:// normais: uma única resposta por id.
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    // "register" é diferente: a TV pode mandar uma mensagem intermediária
    // (type=response, enquanto aguarda o usuário confirmar na tela) antes da
    // mensagem final (type=registered/error) - só a final resolve a espera.
    @Volatile private var registerWaiting: Pair<String, CompletableDeferred<JSONObject>>? = null

    private val nextId = AtomicInteger(1)

    private val client: OkHttpClient by lazy {
        // A TV apresenta um certificado autoassinado para o WebSocket local em
        // wss://host:3001 - não há CA pública nem cadeia para validar (mesma
        // situação de protocolo que online.mmpg.remote.protocol.AcceptAnyServerTrustManager
        // resolve para o Android TV Remote Protocol, mas mantido separado aqui
        // porque o modelo de confiança deste provider é diferente: pareamento
        // confirmado na tela da TV + client-key persistido, não um desafio de
        // PIN). Só é aceitável para conexões LAN a uma TV que o usuário
        // escolheu parear.
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // socket fica aberto indefinidamente entre comandos
            .build()
    }

    /**
     * Fecha qualquer conexão anterior e abre uma nova - nunca deixa dois
     * sockets vivos para o mesmo client. [openTimeoutMs] curto (poucas
     * centenas de ms) é o que torna [LgSubnetScan] viável varrendo 254
     * endereços; o padrão de 8s é para conexão real, não sondagem.
     */
    suspend fun connect(host: String, openTimeoutMs: Long = 8_000): Boolean {
        close()
        for ((scheme, port) in listOf("wss" to 3001, "ws" to 3000)) {
            if (openMainSocket(scheme, host, port, openTimeoutMs)) return true
        }
        return false
    }

    private suspend fun openMainSocket(scheme: String, host: String, port: Int, openTimeoutMs: Long): Boolean {
        val opened = CompletableDeferred<Boolean>()
        onDiag("LG webOS: conectando a $scheme://$host:$port…")
        val ws = client.newWebSocket(Request.Builder().url("$scheme://$host:$port/").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!opened.isCompleted) opened.complete(true)
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onDiag("LG webOS: $scheme://$host:$port falhou (${t.javaClass.simpleName}: ${t.message})")
                if (!opened.isCompleted) opened.complete(false)
                failAllPending()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = failAllPending()
        })
        val success = withTimeoutOrNull(openTimeoutMs) { opened.await() } ?: false
        if (success) mainSocket = ws else ws.cancel()
        return success
    }

    private fun handleMessage(text: String) {
        val json = try { JSONObject(text) } catch (e: Exception) {
            onDiag("LG webOS: mensagem não-JSON ignorada")
            return
        }
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return
        val type = json.optString("type")

        val waiting = registerWaiting
        if (waiting != null && waiting.first == id) {
            if (type == "registered" || type == "error") {
                registerWaiting = null
                waiting.second.complete(json)
            } else {
                onDiag("LG webOS: aguardando confirmação de pareamento na TV (mensagem intermediária: $type)")
            }
            return
        }
        pending.remove(id)?.complete(json)
    }

    private fun failAllPending() {
        val error = JSONObject().put("type", "error")
        registerWaiting?.second?.complete(error)
        registerWaiting = null
        pending.values.forEach { it.complete(error) }
        pending.clear()
    }

    /** Envia "register" e espera até [timeoutMs] pela resposta final (a TV só responde depois que o usuário confirma na tela, ou nunca, se ignorar o prompt). */
    suspend fun register(payload: JSONObject, timeoutMs: Long): JSONObject? {
        val ws = mainSocket ?: return null
        val id = "register_${nextId.getAndIncrement()}"
        val deferred = CompletableDeferred<JSONObject>()
        registerWaiting = id to deferred
        ws.send(JSONObject().put("id", id).put("type", "register").put("payload", payload).toString())
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        registerWaiting = null
        return result
    }

    /** Requisição `ssap://` comum (não usada para os botões de D-pad/volume - ver [pressButton]). */
    suspend fun request(uri: String, payload: JSONObject = JSONObject(), timeoutMs: Long = 8_000): JSONObject? {
        val ws = mainSocket ?: return null
        val id = "req_${nextId.getAndIncrement()}"
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        ws.send(JSONObject().put("id", id).put("type", "request").put("uri", uri).put("payload", payload).toString())
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(id)
        return result
    }

    /**
     * Envia um botão pelo socket especializado de ponteiro/botão
     * (`ssap://com.webos.service.networkinput/getPointerInputSocket`),
     * abrindo-o sob demanda na primeira chamada e reaproveitando depois -
     * abrir um socket novo por tecla seria lento e desnecessário.
     */
    suspend fun pressButton(name: String): Boolean {
        val socket = pointerSocket ?: openPointerSocket() ?: return false
        return socket.send("type:button\nname:$name\n\n")
    }

    private suspend fun openPointerSocket(): WebSocket? {
        val response = request("ssap://com.webos.service.networkinput/getPointerInputSocket") ?: return null
        val socketPath = response.optJSONObject("payload")?.optString("socketPath")?.takeIf { it.isNotBlank() }
            ?: return null
        val opened = CompletableDeferred<WebSocket?>()
        val ws = client.newWebSocket(Request.Builder().url(socketPath).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!opened.isCompleted) opened.complete(webSocket)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened.isCompleted) opened.complete(null)
                if (pointerSocket === webSocket) pointerSocket = null
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (pointerSocket === webSocket) pointerSocket = null
            }
        })
        val result = withTimeoutOrNull(5_000) { opened.await() }
        pointerSocket = result
        if (result == null) ws.cancel()
        return result
    }

    fun close() {
        mainSocket?.close(1000, null)
        mainSocket = null
        pointerSocket?.close(1000, null)
        pointerSocket = null
        failAllPending()
    }
}
