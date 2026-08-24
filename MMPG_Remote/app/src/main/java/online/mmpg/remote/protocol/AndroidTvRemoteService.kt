package online.mmpg.remote.protocol

import android.content.Context

class AndroidTvRemoteService(context: Context) {
    private val store = CertificateStore(context)
    private val pairingClient = PairingClient(store)
    private val remoteClient = RemoteClient(store)

    suspend fun pair(host: String, port: Int, pin: String): String {
        val result = pairingClient.pair(host, port, pin)
        if (result.ok) store.markPaired(host, true)
        return result.toString()
    }

    suspend fun connect(host: String): String {
        if (!store.isPaired(host)) {
            return ProtocolResult(
                false, "NOT_PAIRED", "Pareie esta TV antes de conectar."
            ).toString()
        }
        return remoteClient.connect(host).toString()
    }

    suspend fun sendKey(key: String): String = remoteClient.sendKey(key).toString()

    suspend fun forget(host: String): String {
        remoteClient.close()
        store.forget(host)
        return ProtocolResult(true, "FORGOTTEN", "TV removida deste aparelho.").toString()
    }

    /**
     * Resets the app's shared Keystore identity and every TV's `paired`
     * flag with it (see [CertificateStore.resetClientIdentity]). This is a
     * strictly stronger, rarer action than [forget]: it deauthorizes every
     * paired TV at once, not just one, so it must only ever be triggered by
     * an explicit, clearly-labeled UI action distinct from per-TV
     * "Esquecer" (see AGENTS.md Prompt 6). Any active remote-control session
     * is closed first, since it was authenticated with the identity about
     * to be discarded.
     */
    suspend fun resetIdentity(): String {
        remoteClient.close()
        store.resetClientIdentity()
        return ProtocolResult(
            true, "IDENTITY_RESET", "Identidade do app redefinida. Todas as TVs precisam ser pareadas novamente."
        ).toString()
    }

    fun close() = remoteClient.close()
}
