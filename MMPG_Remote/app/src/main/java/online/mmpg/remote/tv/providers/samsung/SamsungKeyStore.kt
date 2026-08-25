package online.mmpg.remote.tv.providers.samsung

import android.content.Context

/**
 * Persiste, por TV: o token de pareamento (equivalente ao client-key da LG)
 * e o endereço MAC descoberto via [SamsungInfoClient] (usado por
 * [WakeOnLan]). Nem um nem outro é uma chave privada - SharedPreferences
 * comuns bastam, mesmo nível de proteção já usado para o flag
 * `paired:<host>` do Android TV.
 */
class SamsungKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getToken(host: String): String? = prefs.getString("$TOKEN_PREFIX$host", null)

    fun saveToken(host: String, token: String) {
        prefs.edit().putString("$TOKEN_PREFIX$host", token).apply()
    }

    /** Todos os hosts com token salvo - usado pela tela "Minhas TVs" para listar TVs pareadas mesmo offline. */
    fun pairedHosts(): List<String> =
        prefs.all.keys.filter { it.startsWith(TOKEN_PREFIX) }.map { it.removePrefix(TOKEN_PREFIX) }

    fun getMac(host: String): String? = prefs.getString("$MAC_PREFIX$host", null)

    fun saveMac(host: String, mac: String) {
        prefs.edit().putString("$MAC_PREFIX$host", mac).apply()
    }

    /** Nome de exibição salvo no momento do pareamento - só para a UI, sem papel no protocolo. */
    fun saveName(host: String, name: String) {
        prefs.edit().putString("$NAME_PREFIX$host", name).apply()
    }

    fun getName(host: String): String? = prefs.getString("$NAME_PREFIX$host", null)

    fun forget(host: String) {
        prefs.edit()
            .remove("$TOKEN_PREFIX$host")
            .remove("$MAC_PREFIX$host")
            .remove("$NAME_PREFIX$host")
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "mmpg_remote_samsung_devices"
        const val TOKEN_PREFIX = "token:"
        const val MAC_PREFIX = "mac:"
        const val NAME_PREFIX = "name:"
    }
}
