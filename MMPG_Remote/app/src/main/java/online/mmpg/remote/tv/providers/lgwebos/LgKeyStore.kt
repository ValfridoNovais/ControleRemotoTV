package online.mmpg.remote.tv.providers.lgwebos

import android.content.Context

/**
 * Persiste o "client-key" que o LG webOS devolve depois que o usuário aceita
 * o pareamento na TV (ver [LgSsapClient.register]) - um token de autorização
 * por TV, não uma chave privada; basta SharedPreferences comuns, o mesmo
 * nível de proteção já usado para o flag `paired:<host>` do Android TV (ver
 * [online.mmpg.remote.protocol.CertificateStore.markPaired]/`isPaired`).
 */
class LgKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(host: String): String? = prefs.getString("$KEY_PREFIX$host", null)

    fun save(host: String, clientKey: String) {
        prefs.edit().putString("$KEY_PREFIX$host", clientKey).apply()
    }

    /** Todos os hosts com client-key salvo - usado pela tela "Minhas TVs" para listar TVs pareadas mesmo offline. */
    fun pairedHosts(): List<String> =
        prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.map { it.removePrefix(KEY_PREFIX) }

    /** Nome de exibição salvo no momento do pareamento - só para a UI, sem papel no protocolo. */
    fun saveName(host: String, name: String) {
        prefs.edit().putString("$NAME_PREFIX$host", name).apply()
    }

    fun getName(host: String): String? = prefs.getString("$NAME_PREFIX$host", null)

    fun forget(host: String) {
        prefs.edit().remove("$KEY_PREFIX$host").remove("$NAME_PREFIX$host").apply()
    }

    private companion object {
        const val PREFS_NAME = "mmpg_remote_lg_devices"
        const val KEY_PREFIX = "client_key:"
        const val NAME_PREFIX = "name:"
    }
}
