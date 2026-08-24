package online.mmpg.remote.protocol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.security.auth.x500.X500Principal

/**
 * Owns two things:
 *  1. This app's persistent TLS client identity (an RSA key pair and a
 *     self-signed certificate) used to authenticate with Android TVs during
 *     pairing and future remote-control sessions. The private key is
 *     generated inside and never leaves the Android Keystore (hardware-backed
 *     when the device supports it); this class never sees or stores its raw
 *     bytes. The same identity is intentionally shared across every TV the
 *     app pairs with - it is the app's own identity, not a per-TV secret -
 *     which is what lets a future reconnect (Prompt 4) skip re-pairing.
 *  2. A lightweight, non-sensitive `paired:<host>` boolean per TV in
 *     SharedPreferences, used only to gate whether this app will attempt to
 *     reconnect to a given host without pairing again.
 *
 * The pairing PIN never passes through this class and is never persisted
 * anywhere - it only ever exists in memory for the duration of a single
 * pairing attempt in [PairingClient].
 */
class CertificateStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("mmpg_remote_devices", Context.MODE_PRIVATE)

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun markPaired(host: String, paired: Boolean) {
        prefs.edit().putBoolean("$PAIRED_KEY_PREFIX$host", paired).apply()
    }

    fun isPaired(host: String): Boolean = prefs.getBoolean("$PAIRED_KEY_PREFIX$host", false)

    /**
     * Removes every persisted trace of pairing with [host] and only [host] -
     * currently just its `paired:<host>` flag, which is the sole per-host
     * value this store persists (audited in Prompt 6: no other per-host data
     * - certificate, name, port, etc. - is ever written to SharedPreferences
     * or any other file). Other paired TVs, and the shared Keystore identity
     * itself, are left completely untouched; see [resetClientIdentity] for
     * the separate, stronger action that does affect every TV.
     */
    fun forget(host: String) {
        prefs.edit().remove("$PAIRED_KEY_PREFIX$host").apply()
        // The client certificate/key identity is shared across every TV (see
        // class doc) - it is intentionally NOT deleted here. Removing it would
        // force re-pairing on every other already-paired TV as a side effect
        // of forgetting just one of them. A future "reset app identity"
        // action (out of scope for this increment) can call
        // resetClientIdentity() explicitly if that stronger reset is wanted.
    }

    /**
     * Returns the app's client certificate, generating a fresh RSA key pair
     * and self-signed certificate in the Android Keystore on first use. The
     * same identity is reused for every TV and across app restarts.
     */
    fun getOrCreateClientCertificate(): X509Certificate {
        (keyStore.getCertificate(CLIENT_ALIAS) as? X509Certificate)?.let { return it }

        Log.i(TAG, "Nenhum certificado de cliente encontrado; gerando identidade no Android Keystore")
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        val notBefore = Date()
        val notAfter = Calendar.getInstance().apply {
            time = notBefore
            add(Calendar.YEAR, CERTIFICATE_VALIDITY_YEARS)
        }.time
        val spec = KeyGenParameterSpec.Builder(
            CLIENT_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setDigests(
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512
            )
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                KeyProperties.SIGNATURE_PADDING_RSA_PSS
            )
            .setCertificateSubject(X500Principal("CN=$CLIENT_COMMON_NAME"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(notBefore)
            .setCertificateNotAfter(notAfter)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
        Log.i(TAG, "Identidade de cliente gerada com sucesso")

        return keyStore.getCertificate(CLIENT_ALIAS) as X509Certificate
    }

    /**
     * Builds the [KeyManager]s backed by the Keystore identity above, ready to
     * be plugged into an [javax.net.ssl.SSLContext] for mutual-TLS pairing or
     * remote-control sessions. Ensures the certificate exists first.
     */
    fun getKeyManagers(): Array<KeyManager> {
        getOrCreateClientCertificate()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        // AndroidKeyStore-backed entries have no exportable password; null is
        // the documented/expected value here.
        kmf.init(keyStore, null)
        return kmf.keyManagers
    }

    /**
     * Deletes the app's entire client identity (a fresh RSA key pair/
     * certificate will be generated on next use) and clears every per-host
     * `paired:<host>` flag. Both steps are required for this to be honest:
     * once the Keystore identity changes, none of the TVs previously paired
     * against the old certificate will accept this app's new one, so leaving
     * their `paired:<host>` flags set would make [isPaired] lie and a later
     * reconnect attempt would fail confusingly instead of prompting the user
     * to re-pair. This is a deliberately separate, rarer action from
     * [forget] - forgetting a single TV must never call this, since it would
     * silently deauthorize every *other* already-paired TV as a side effect
     * (see [forget] doc). Wired to the "Redefinir identidade do app" action
     * in the UI (see AGENTS.md Prompt 6), distinct from per-TV "Esquecer".
     */
    fun resetClientIdentity() {
        if (keyStore.containsAlias(CLIENT_ALIAS)) {
            keyStore.deleteEntry(CLIENT_ALIAS)
            Log.i(TAG, "Identidade de cliente removida do Android Keystore")
        }
        val pairedKeys = prefs.all.keys.filter { it.startsWith(PAIRED_KEY_PREFIX) }
        if (pairedKeys.isNotEmpty()) {
            val editor = prefs.edit()
            pairedKeys.forEach { editor.remove(it) }
            editor.apply()
            Log.i(TAG, "Flags de pareamento de ${pairedKeys.size} TV(s) limpos após redefinição de identidade")
        }
    }

    private companion object {
        private const val TAG = "CertificateStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CLIENT_ALIAS = "mmpg_remote_client_identity"
        private const val CLIENT_COMMON_NAME = "MMPG Remote"
        private const val CERTIFICATE_VALIDITY_YEARS = 10
        private const val PAIRED_KEY_PREFIX = "paired:"
    }
}
