package online.mmpg.remote.tv.providers.samsung

import android.util.Base64
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
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Transporte WebSocket para o protocolo (não documentado oficialmente,
 * reverso-projetado pela comunidade) de controle remoto de TVs Samsung
 * Tizen (2016+). Formato de URL, payload de tecla e nomes de evento de
 * pareamento vêm de xchwarze/samsung-tv-ws-api (LGPL-3.0, consultado só
 * como referência de protocolo - nenhum trecho de código copiado) - ver
 * docs/providers/samsung-tizen.md e THIRD_PARTY_NOTICES.md.
 */
class SamsungWsClient(
    private val appName: String,
    private val onDiag: (String) -> Unit = {}
) {
    private var socket: WebSocket? = null

    private val client: OkHttpClient by lazy {
        // Mesmo modelo de confiança do LgSsapClient: a TV apresenta um
        // certificado autoassinado para o WebSocket local em wss://host:8002 -
        // a confiança real vem do prompt de confirmação na tela da TV +
        // token persistido, não da cadeia de certificados.
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
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    sealed class ConnectOutcome {
        data class Paired(val token: String?) : ConnectOutcome()
        object Rejected : ConnectOutcome()
        object Timeout : ConnectOutcome()
        object ConnectionFailed : ConnectOutcome()
    }

    /**
     * Tenta `wss://:8002` (TLS, exigido por modelos Tizen mais recentes) e,
     * só se o socket nem chegar a abrir, cai para `ws://:8001` (modelos
     * 2016-2018) - descoberto revisando issues reais de
     * xchwarze/samsung-tv-ws-api/Home Assistant depois que a descoberta
     * SSDP já tinha se mostrado pouco confiável (ver
     * docs/providers/samsung-tizen.md). Só troca de porta quando a conexão
     * TCP/TLS em si falha - nunca depois que ela abriu, porque nesse ponto
     * a TV já pode ter mostrado o prompt de confirmação na tela, e tentar
     * de novo criaria um segundo prompt confuso para o usuário.
     */
    suspend fun connect(host: String, savedToken: String?, timeoutMs: Long): ConnectOutcome {
        close()
        for ((scheme, port) in listOf("wss" to 8002, "ws" to 8001)) {
            val outcome = tryConnect(scheme, host, port, savedToken, timeoutMs)
            if (outcome !is ConnectOutcome.ConnectionFailed) return outcome
        }
        return ConnectOutcome.ConnectionFailed
    }

    /**
     * Abre o WebSocket num esquema/porta e espera até [timeoutMs] pelo
     * evento final de pareamento - a TV só manda algo depois que o usuário
     * aceita (ou rejeita) o prompt na tela, ou nunca, se ignorar. Eventos
     * que não são nem sucesso nem rejeição são ignorados (a TV manda alguns
     * eventos de status intermediários cujo conjunto exato não está
     * documentado publicamente) - só timeout ou uma resposta reconhecida
     * encerram a espera.
     */
    private suspend fun tryConnect(scheme: String, host: String, port: Int, savedToken: String?, timeoutMs: Long): ConnectOutcome {
        val encodedName = Base64.encodeToString(appName.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val tokenQuery = savedToken?.let { "&token=$it" } ?: ""
        val url = "$scheme://$host:$port/api/v2/channels/$REMOTE_ENDPOINT?name=$encodedName$tokenQuery"

        val opened = CompletableDeferred<Boolean>()
        val resolved = CompletableDeferred<ConnectOutcome>()
        onDiag("Samsung: conectando a $scheme://$host:$port…")
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!opened.isCompleted) opened.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try { JSONObject(text) } catch (e: Exception) {
                    onDiag("Samsung: mensagem não-JSON ignorada")
                    return
                }
                when (val event = json.optString("event")) {
                    EVENT_CONNECT -> {
                        val token = json.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }
                        if (!resolved.isCompleted) resolved.complete(ConnectOutcome.Paired(token))
                    }
                    EVENT_UNAUTHORIZED -> {
                        if (!resolved.isCompleted) resolved.complete(ConnectOutcome.Rejected)
                    }
                    else -> onDiag("Samsung: evento intermediário ignorado ($event)")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onDiag("Samsung: $scheme://$host:$port falhou (${t.javaClass.simpleName}: ${t.message})")
                if (!opened.isCompleted) opened.complete(false)
                if (!resolved.isCompleted) resolved.complete(ConnectOutcome.ConnectionFailed)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!resolved.isCompleted) resolved.complete(ConnectOutcome.ConnectionFailed)
            }
        })

        val didOpen = withTimeoutOrNull(8_000) { opened.await() } ?: false
        if (!didOpen) {
            ws.cancel()
            return ConnectOutcome.ConnectionFailed
        }
        socket = ws
        return withTimeoutOrNull(timeoutMs) { resolved.await() } ?: ConnectOutcome.Timeout
    }

    fun pressKey(keyCode: String): Boolean {
        val ws = socket ?: return false
        val message = JSONObject().apply {
            put("method", "ms.remote.control")
            put(
                "params",
                JSONObject().apply {
                    put("Cmd", "Click")
                    put("DataOfCmd", keyCode)
                    put("Option", "false")
                    put("TypeOfRemote", "SendRemoteKey")
                }
            )
        }
        return ws.send(message.toString())
    }

    fun close() {
        socket?.close(1000, null)
        socket = null
    }

    private companion object {
        const val REMOTE_ENDPOINT = "samsung.remote.control"
        // Valores reais enviados no protocolo (confirmados no código-fonte de
        // event.py, não em resumos de terceiros) - "MS_CHANNEL_CONNECT_EVENT"
        // (maiúsculo) usado numa versão anterior deste arquivo era o nome da
        // constante Python, não a string que a TV realmente manda; isso fazia
        // o app nunca reconhecer a confirmação do usuário na TV e estourar
        // timeout mesmo depois do pareamento aceito. Ver docs/providers/samsung-tizen.md.
        const val EVENT_CONNECT = "ms.channel.connect"
        const val EVENT_UNAUTHORIZED = "ms.channel.unauthorized"
    }
}
