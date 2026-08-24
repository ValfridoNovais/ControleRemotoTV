package online.mmpg.remote.protocol

import android.content.Context

class AndroidTvRemoteService(
    context: Context,
    /** Mirrors lifecycle/error messages from the protocol layer to the UI; see [online.mmpg.remote.TvBridge]. */
    onDiag: (String) -> Unit = {}
) {
    private val store = CertificateStore(context, onDiag)
    private val pairingClient = PairingClient(store, onDiag)
    private val remoteClient = RemoteClient(store, onDiag)

    /** Steps 1-6 of pairing: connects and negotiates until the TV shows its PIN. */
    suspend fun beginPairing(host: String, port: Int): String =
        pairingClient.beginPairing(host, port).toString()

    /** Steps 7-8 of pairing: sends the PIN-derived secret and completes pairing. */
    suspend fun submitPairingPin(host: String, pin: String): String {
        val result = pairingClient.submitPin(pin)
        if (result.ok) store.markPaired(host, true)
        return result.toString()
    }

    /** Cancels a pairing session opened by [beginPairing] without completing it. */
    fun cancelPairing() = pairingClient.cancelPairing()

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

    fun close() {
        pairingClient.cancelPairing()
        remoteClient.close()
    }
}
