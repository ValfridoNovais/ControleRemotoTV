package online.mmpg.remote.protocol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Calendar
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.security.auth.x500.X500Principal

/**
 * Owns two things:
 *  1. This app's persistent TLS client identity (an RSA key pair and a
 *     self-signed certificate) used to authenticate with Android TVs during
 *     pairing and future remote-control sessions. The same identity is
 *     intentionally shared across every TV the app pairs with - it is the
 *     app's own identity, not a per-TV secret - which is what lets a
 *     reconnect skip re-pairing.
 *  2. A lightweight, non-sensitive `paired:<host>` boolean per TV in
 *     SharedPreferences, used only to gate whether this app will attempt to
 *     reconnect to a given host without pairing again.
 *
 * ## Why the private key is software-generated, not AndroidKeyStore-generated
 *
 * The original design generated the RSA key pair directly inside
 * `AndroidKeyStore` (hardware-backed on devices that support it). That is
 * normally the right call - but live-tested against a real Android TV
 * (UnionTV-based firmware), the TLS handshake on port 6467 consistently
 * failed with a generic BoringSSL/Conscrypt `SSLHandshakeException` ("RSA
 * routines: internal error") the instant the client tried to sign the
 * CertificateVerify message - regardless of TLS version (default negotiation
 * and TLS 1.2-forced both failed identically) and regardless of declared key
 * capabilities (both PKCS#1-only and PKCS#1+PSS failed identically). The key
 * was confirmed hardware-backed (`KeyInfo.isInsideSecureHardware() == true`).
 * That combination - correct declared capabilities, correct TLS negotiation,
 * failure only when the hardware-backed key actually has to sign - points at
 * a broken RSA-signing path in this device's secure hardware (TEE/Keymaster)
 * itself, which no key-attribute tuning at the Android API level can work
 * around.
 *
 * The fix is to generate the RSA key pair in software instead (standard JCA,
 * no AndroidKeyStore provider involved in the actual signing operation), and
 * protect it at rest using envelope encryption: an AES-256-GCM key generated
 * inside `AndroidKeyStore` (a much simpler, near-universally reliable
 * Keystore operation than RSA signing) encrypts the RSA private key's PKCS#8
 * bytes before they're written to SharedPreferences. The raw RSA private key
 * only ever exists decrypted in memory, for the duration of building the
 * `KeyManager`s for one TLS handshake.
 *
 * The pairing PIN never passes through this class and is never persisted
 * anywhere - it only ever exists in memory for the duration of a single
 * pairing attempt in [PairingClient].
 */
class CertificateStore(
    context: Context,
    /** Mirrors lifecycle/diagnostic messages for an in-app log; see [online.mmpg.remote.TvBridge]. */
    private val onDiag: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("mmpg_remote_devices", Context.MODE_PRIVATE)

    private fun logI(msg: String) { Log.i(TAG, msg); onDiag(msg) }

    private val androidKeyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun markPaired(host: String, paired: Boolean) {
        prefs.edit().putBoolean("$PAIRED_KEY_PREFIX$host", paired).apply()
    }

    fun isPaired(host: String): Boolean = prefs.getBoolean("$PAIRED_KEY_PREFIX$host", false)

    /** Todos os hosts com `paired:<host>=true` - usado pela tela "Minhas TVs" para listar TVs pareadas mesmo offline. */
    fun pairedHosts(): List<String> =
        prefs.all.keys
            .filter { it.startsWith(PAIRED_KEY_PREFIX) && prefs.getBoolean(it, false) }
            .map { it.removePrefix(PAIRED_KEY_PREFIX) }

    /**
     * Nome de exibição salvo no momento do pareamento (vem do último
     * `TvDevice` visto na descoberta - ver [online.mmpg.remote.tv.providers.AndroidTvProvider]).
     * Só para a UI "Minhas TVs" mostrar um nome legível em vez do IP puro;
     * não tem nenhum papel no protocolo/segurança.
     */
    fun saveName(host: String, name: String) {
        prefs.edit().putString("$NAME_KEY_PREFIX$host", name).apply()
    }

    fun getName(host: String): String? = prefs.getString("$NAME_KEY_PREFIX$host", null)

    /**
     * Removes every persisted trace of pairing with [host] and only [host] -
     * seu flag `paired:<host>` e nome salvo. Other paired TVs, and the
     * shared client identity itself, are left completely untouched; see
     * [resetClientIdentity] for the separate, stronger action that does
     * affect every TV.
     */
    fun forget(host: String) {
        prefs.edit()
            .remove("$PAIRED_KEY_PREFIX$host")
            .remove("$NAME_KEY_PREFIX$host")
            .apply()
        // The client certificate/key identity is shared across every TV (see
        // class doc) - it is intentionally NOT deleted here. Removing it
        // would force re-pairing on every other already-paired TV as a side
        // effect of forgetting just one of them.
    }

    /**
     * Returns the app's client certificate, generating a fresh software RSA
     * key pair and self-signed certificate on first use (see class doc for
     * why this is software rather than AndroidKeyStore-generated). The same
     * identity is reused for every TV and across app restarts.
     */
    fun getOrCreateClientCertificate(): X509Certificate {
        loadCertificate()?.let { return it }

        logI("Nenhuma identidade de cliente encontrada; gerando par de chaves RSA em software")
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val notBefore = Date()
        val notAfter = Calendar.getInstance().apply {
            time = notBefore
            add(Calendar.YEAR, CERTIFICATE_VALIDITY_YEARS)
        }.time
        val subject = X500Principal("CN=$CLIENT_COMMON_NAME")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.currentTimeMillis()),
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        persistPrivateKey(keyPair.private)
        persistCertificate(cert)
        logI("Identidade de cliente (software, RSA-2048) gerada com sucesso")
        return cert
    }

    /**
     * Builds the [KeyManager]s backed by the identity above, ready to be
     * plugged into an [javax.net.ssl.SSLContext] for mutual-TLS pairing or
     * remote-control sessions. Ensures the identity exists first. The
     * private key is only ever decrypted in memory for the lifetime of the
     * in-memory [KeyStore] built here, for this one call.
     */
    fun getKeyManagers(): Array<KeyManager> {
        val cert = getOrCreateClientCertificate()
        val privateKey = loadPrivateKey()
            ?: throw IllegalStateException("Identidade de cliente inconsistente: certificado sem chave privada correspondente.")

        val inMemoryStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        inMemoryStore.setKeyEntry(CLIENT_ALIAS, privateKey, CharArray(0), arrayOf(cert))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(inMemoryStore, CharArray(0))
        return kmf.keyManagers
    }

    /**
     * Deletes the app's entire client identity (a fresh key pair/certificate
     * will be generated on next use) and clears every per-host
     * `paired:<host>` flag. Both steps are required for this to be honest:
     * once the identity changes, none of the TVs previously paired against
     * the old certificate will accept this app's new one, so leaving their
     * `paired:<host>` flags set would make [isPaired] lie. This is a
     * deliberately separate, rarer action from [forget] - forgetting a
     * single TV must never call this, since it would silently deauthorize
     * every *other* already-paired TV as a side effect. Wired to the
     * "Redefinir identidade do app" action in the UI, distinct from per-TV
     * "Esquecer".
     */
    fun resetClientIdentity() {
        val editor = prefs.edit()
        var hadIdentity = false
        if (prefs.contains(PRIVATE_KEY_PREF) || prefs.contains(CERT_PREF)) {
            editor.remove(PRIVATE_KEY_PREF)
            editor.remove(PRIVATE_KEY_IV_PREF)
            editor.remove(CERT_PREF)
            hadIdentity = true
        }
        if (androidKeyStore.containsAlias(WRAP_KEY_ALIAS)) {
            androidKeyStore.deleteEntry(WRAP_KEY_ALIAS)
            hadIdentity = true
        }
        if (hadIdentity) {
            logI("Identidade de cliente removida")
        }
        val pairedKeys = prefs.all.keys.filter { it.startsWith(PAIRED_KEY_PREFIX) }
        pairedKeys.forEach { editor.remove(it) }
        editor.apply()
        if (pairedKeys.isNotEmpty()) {
            logI("Flags de pareamento de ${pairedKeys.size} TV(s) limpos após redefinição de identidade")
        }
    }

    // --- Envelope encryption: AES-256-GCM key in AndroidKeyStore wraps the
    // --- software RSA private key's PKCS#8 bytes at rest. AES via Keystore
    // --- is a far simpler, more broadly reliable operation than RSA signing
    // --- and is not implicated in the handshake failure this works around.

    private fun getOrCreateWrappingKey(): SecretKey {
        (androidKeyStore.getKey(WRAP_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun persistPrivateKey(privateKey: PrivateKey) {
        val wrapKey = getOrCreateWrappingKey()
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey)
        val encrypted = cipher.doFinal(privateKey.encoded)
        prefs.edit()
            .putString(PRIVATE_KEY_PREF, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(PRIVATE_KEY_IV_PREF, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    private fun loadPrivateKey(): PrivateKey? {
        val encoded = prefs.getString(PRIVATE_KEY_PREF, null) ?: return null
        val iv = prefs.getString(PRIVATE_KEY_IV_PREF, null) ?: return null
        val wrapKey = (androidKeyStore.getKey(WRAP_KEY_ALIAS, null) as? SecretKey) ?: return null
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrapKey, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        val decrypted = cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP))
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decrypted))
    }

    private fun persistCertificate(cert: X509Certificate) {
        prefs.edit().putString(CERT_PREF, Base64.encodeToString(cert.encoded, Base64.NO_WRAP)).apply()
    }

    private fun loadCertificate(): X509Certificate? {
        val encoded = prefs.getString(CERT_PREF, null) ?: return null
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(Base64.decode(encoded, Base64.NO_WRAP).inputStream()) as X509Certificate
    }

    private companion object {
        private const val TAG = "CertificateStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAP_KEY_ALIAS = "mmpg_remote_wrap_key"
        private const val CLIENT_ALIAS = "mmpg_remote_client_identity"
        private const val CLIENT_COMMON_NAME = "MMPG Remote"
        private const val CERTIFICATE_VALIDITY_YEARS = 10
        private const val PAIRED_KEY_PREFIX = "paired:"
        private const val NAME_KEY_PREFIX = "name:"
        private const val PRIVATE_KEY_PREF = "client_private_key_enc"
        private const val PRIVATE_KEY_IV_PREF = "client_private_key_iv"
        private const val CERT_PREF = "client_certificate"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
