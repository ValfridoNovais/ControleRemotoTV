package online.mmpg.remote.protocol

import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager

/**
 * Opens a mutual-TLS socket for the Android TV Remote Protocol v2
 * (pairing on 6467, remote control on 6466), shared by [PairingClient] and
 * [RemoteClient].
 *
 * By default this lets the platform negotiate its full supported TLS range
 * (up to TLS 1.3 on current Android/Conscrypt) rather than pinning a fixed
 * version - the goal is to use the best protocol the phone and TV both
 * support, not the lowest common denominator.
 *
 * Some AndroidKeyStore-backed RSA keys hit a known Conscrypt/BoringSSL
 * incompatibility with TLS 1.3's CertificateVerify step (which requires
 * RSA-PSS exclusively): the handshake fails with a generic
 * `SSLHandshakeException` whose message names OpenSSL's "RSA routines" with
 * an "internal error", carrying no protocol-level detail. That failure is
 * device/Conscrypt-version-specific, not something every install needs to
 * pay for - so this only reacts to it: on exactly that failure signature, it
 * retries once, automatically, with a fresh socket restricted to TLS 1.2
 * (whose client-cert signatures use PKCS#1 v1.5, a far more mature code path
 * for Keystore-backed keys). Any other handshake failure is surfaced as-is.
 */
internal object TlsConnector {
    fun connect(
        host: String,
        port: Int,
        keyManagers: Array<KeyManager>,
        trustManagers: Array<TrustManager>,
        connectTimeoutMs: Int,
        handshakeTimeoutMs: Int,
        onDiag: (String) -> Unit = {}
    ): SSLSocket {
        return try {
            handshake(host, port, keyManagers, trustManagers, connectTimeoutMs, handshakeTimeoutMs, protocols = null, onDiag)
        } catch (e: SSLHandshakeException) {
            if (!looksLikeKeystoreTls13Bug(e)) throw e
            onDiag(
                "Handshake TLS falhou na negociação padrão " +
                    "(${e.message?.take(120)}); tentando novamente forçando TLS 1.2 " +
                    "(possível incompatibilidade conhecida entre TLS 1.3 e chave do Keystore)"
            )
            handshake(host, port, keyManagers, trustManagers, connectTimeoutMs, handshakeTimeoutMs, protocols = arrayOf("TLSv1.2"), onDiag)
        }
    }

    private fun handshake(
        host: String,
        port: Int,
        keyManagers: Array<KeyManager>,
        trustManagers: Array<TrustManager>,
        connectTimeoutMs: Int,
        handshakeTimeoutMs: Int,
        protocols: Array<String>?,
        onDiag: (String) -> Unit
    ): SSLSocket {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagers, trustManagers, SecureRandom())

        var socket: Socket? = null
        try {
            val plainSocket = Socket()
            socket = plainSocket
            plainSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)

            val sslSocket = sslContext.socketFactory.createSocket(plainSocket, host, port, true) as SSLSocket
            socket = sslSocket
            if (protocols != null) {
                sslSocket.enabledProtocols = protocols
            }
            sslSocket.soTimeout = handshakeTimeoutMs
            sslSocket.startHandshake()
            onDiag("Handshake TLS concluído (protocolo=${sslSocket.session.protocol}, cipher=${sslSocket.session.cipherSuite})")
            socket = null // handshake succeeded; caller now owns the socket
            return sslSocket
        } finally {
            socket?.let {
                try {
                    it.close()
                } catch (_: Exception) {
                    // Best-effort cleanup on a failed attempt.
                }
            }
        }
    }

    private fun looksLikeKeystoreTls13Bug(e: SSLHandshakeException): Boolean {
        val msg = e.message ?: return false
        return msg.contains("RSA routines", ignoreCase = true) ||
            msg.contains("OPENSSL_internal", ignoreCase = true)
    }
}
