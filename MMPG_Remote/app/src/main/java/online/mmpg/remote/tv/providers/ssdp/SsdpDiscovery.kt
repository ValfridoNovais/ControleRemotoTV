package online.mmpg.remote.tv.providers.ssdp

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Descoberta SSDP genérica, compartilhada por qualquer [online.mmpg.remote.tv.TvProvider]
 * que precise dela (LG webOS e Samsung Tizen até agora - cada fabricante
 * usa seu próprio Search Target, mas o mecanismo de M-SEARCH/multicast é
 * idêntico). Extraída de uma versão específica de LG depois que um segundo
 * provider (Samsung) precisou exatamente da mesma coisa - mesmo espírito de
 * [online.mmpg.remote.discovery.TvDeviceRegistry] ter sido extraída de
 * TvDiscovery: lógica compartilhada vira uma classe própria assim que tem
 * um segundo usuário real, não antes.
 *
 * Ao contrário do NSD (escuta contínua), SSDP aqui é uma varredura pontual:
 * um M-SEARCH é enviado por multicast e as respostas são coletadas por
 * [durationMs] a cada chamada de [search].
 *
 * `CHANGE_WIFI_MULTICAST_STATE` (já declarada no AndroidManifest para o NSD)
 * é o que permite obter o [WifiManager.MulticastLock] necessário para que o
 * multicast realmente chegue a este app em muitos aparelhos Android.
 */
class SsdpDiscovery(
    context: Context,
    private val onDiag: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private var job: Job? = null

    data class Found(val host: String, val locationUrl: String?, val friendlyName: String?)

    fun search(scope: CoroutineScope, searchTarget: String, durationMs: Long = 4_000, onFound: (Found) -> Unit) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi?.createMulticastLock("mmpg_remote_ssdp")?.apply { setReferenceCounted(true) }
            lock?.acquire()
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply { soTimeout = 1000 }
                val group = InetAddress.getByName(MULTICAST_ADDRESS)
                val message = searchRequest(searchTarget).toByteArray(Charsets.US_ASCII)
                socket.send(DatagramPacket(message, message.size, InetSocketAddress(group, MULTICAST_PORT)))
                onDiag("SSDP: M-SEARCH enviado para $searchTarget")

                val seen = mutableSetOf<String>()
                val deadline = System.currentTimeMillis() + durationMs
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val response = DatagramPacket(buffer, buffer.size)
                        socket.receive(response)
                        val text = String(response.data, 0, response.length, Charsets.US_ASCII)
                        if (!text.contains(searchTarget, ignoreCase = true)) continue
                        val host = response.address.hostAddress ?: continue
                        if (!seen.add(host)) continue
                        onDiag("SSDP: resposta de $host para $searchTarget")
                        val location = extractHeader(text, "LOCATION")
                        val friendlyName = location?.let { fetchFriendlyName(it) }
                        onFound(Found(host, location, friendlyName))
                    } catch (e: SocketTimeoutException) {
                        // normal - só volta a checar o deadline
                    }
                }
            } catch (e: IOException) {
                onDiag("SSDP: erro de rede na busca (${e.javaClass.simpleName})")
            } finally {
                socket?.close()
                try { lock?.release() } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun extractHeader(raw: String, header: String): String? {
        val line = raw.lineSequence().firstOrNull { it.startsWith("$header:", ignoreCase = true) } ?: return null
        return line.substringAfter(":").trim().takeIf { it.isNotBlank() }
    }

    /**
     * Busca `<friendlyName>` no XML de descrição do dispositivo UPnP (campo
     * padrão da especificação UPnP, presente em qualquer dispositivo SSDP,
     * não uma extensão específica de um fabricante) - melhor esforço só para
     * exibir um nome legível; `null` se o dispositivo não responder ou o XML
     * não tiver o campo.
     */
    private fun fetchFriendlyName(locationUrl: String): String? = try {
        val conn = (URL(locationUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2000
            readTimeout = 2000
        }
        conn.inputStream.use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            var eventType = parser.eventType
            var inFriendlyName = false
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> inFriendlyName = parser.name.equals("friendlyName", ignoreCase = true)
                    XmlPullParser.TEXT -> if (inFriendlyName) {
                        val name = parser.text?.trim()
                        if (!name.isNullOrBlank()) return name
                    }
                }
                eventType = parser.next()
            }
        }
        null
    } catch (e: Exception) {
        null
    }

    private fun searchRequest(searchTarget: String) = "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $MULTICAST_ADDRESS:$MULTICAST_PORT\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 3\r\n" +
        "ST: $searchTarget\r\n" +
        "USER-AGENT: MMPG Remote/1.0 UDAP/2.0\r\n\r\n"

    private companion object {
        const val MULTICAST_ADDRESS = "239.255.255.250"
        const val MULTICAST_PORT = 1900
    }
}
