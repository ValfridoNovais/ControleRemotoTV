package online.mmpg.remote.tv.providers.samsung

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GET não autenticado que devolve nome/modelo/MAC da TV
 * (`http://host:8001/api/v2/`) - não é parte do protocolo de controle em si
 * (isso é [SamsungWsClient]), só uma forma de identificar o aparelho e obter
 * o endereço MAC para Wake-on-LAN sem precisar estar pareado. Ver
 * docs/providers/samsung-tizen.md.
 */
object SamsungInfoClient {
    data class Info(val name: String?, val modelName: String?, val wifiMac: String?)

    fun fetch(host: String, timeoutMs: Int = 2000): Info? = try {
        val conn = (URL("http://$host:$PORT/api/v2/").openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val device = JSONObject(body).optJSONObject("device")
        Info(
            name = device?.optString("name")?.takeIf { it.isNotBlank() },
            modelName = device?.optString("modelName")?.takeIf { it.isNotBlank() },
            wifiMac = device?.optString("wifiMac")?.takeIf { it.isNotBlank() }
        )
    } catch (e: Exception) {
        null
    }

    private const val PORT = 8001
}
