package online.mmpg.remote.protocol

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * Android TV Remote Protocol v2 pairing client (TCP/TLS, port 6467).
 *
 * Performs the real pairing handshake against the TV: mutual TLS using this
 * app's persistent Keystore-backed client certificate ([CertificateStore]),
 * then the protobuf message exchange below (see [PairingMessages] for the
 * exact message shapes/field numbers, cross-checked against
 * tronikos/androidtvremote2 and louis49/androidtv-remote - see
 * THIRD_PARTY_NOTICES.md):
 *
 *   1. client -> TV : PairingRequest      (client_name, service_name)
 *   2. TV -> client : PairingRequestAck
 *   3. client -> TV : Options             (preferred encoding: 6-digit hex, role INPUT)
 *   4. TV -> client : Options             (TV's own encoding/role reply)
 *   5. client -> TV : Configuration       (confirms encoding/role)
 *   6. TV -> client : ConfigurationAck    (TV now shows the PIN on screen)
 *   7. client -> TV : Secret              (SHA-256 challenge computed from both
 *                                          certificates' RSA public keys + PIN)
 *   8. TV -> client : SecretAck           (pairing complete) or an error status
 *
 * Steps 1-6 are [beginPairing] and steps 7-8 are [submitPin] - split into two
 * calls because the TV only displays the PIN *after* step 6, so the caller
 * cannot know the PIN before that point. The TLS socket is kept open between
 * the two calls (see [session]); [cancelPairing] or a fresh [beginPairing]
 * always closes any previously open one first, so nothing leaks.
 *
 * This never fabricates success: every step requires a real response from
 * the TV over a real TLS socket, and any network/protocol/PIN failure is
 * surfaced as a specific [ProtocolResult] instead.
 *
 * The PIN is held in memory only for the duration of [submitPin] (as the
 * plain parameter and the derived SHA-256 secret) and is never logged or
 * persisted; only high-level lifecycle events are logged.
 */
class PairingClient(
    private val certificateStore: CertificateStore,
    /** Mirrors lifecycle/error messages for an in-app diagnostic log; see [online.mmpg.remote.TvBridge]. */
    private val onDiag: (String) -> Unit = {}
) {
    private fun logI(msg: String) { Log.i(TAG, msg); onDiag(msg) }
    private fun logW(msg: String) { Log.w(TAG, msg); onDiag(msg) }
    private fun logE(msg: String, e: Throwable) { Log.e(TAG, msg, e); onDiag("$msg (${e.javaClass.simpleName}: ${e.message})") }

    private class Session(
        val socket: Socket,
        val input: InputStream,
        val output: java.io.OutputStream,
        val clientKey: RSAPublicKey,
        val serverKey: RSAPublicKey
    )

    @Volatile
    private var session: Session? = null

    /** Runs steps 1-6: connects and negotiates up to the point the TV shows its PIN. */
    suspend fun beginPairing(host: String, port: Int): ProtocolResult = withContext(Dispatchers.IO) {
        cancelPairing()

        var socket: Socket? = null
        try {
            logI("Iniciando handshake TLS de pareamento com $host:$port")
            val clientCert = certificateStore.getOrCreateClientCertificate()

            val sslSocket = TlsConnector.connect(
                host = host,
                port = port,
                keyManagers = certificateStore.getKeyManagers(),
                trustManagers = arrayOf(AcceptAnyServerTrustManager),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                handshakeTimeoutMs = IO_TIMEOUT_MS,
                onDiag = ::logI
            )
            socket = sslSocket
            logI("Iniciando troca de mensagens de pareamento")

            val serverCert = sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: return@withContext ProtocolResult(false, "TLS_ERROR", "A TV não apresentou um certificado válido.")

            val clientKey = clientCert.publicKey as? RSAPublicKey
            val serverKey = serverCert.publicKey as? RSAPublicKey
            if (clientKey == null || serverKey == null) {
                return@withContext ProtocolResult(false, "TLS_ERROR", "Certificado incompatível (esperado RSA).")
            }

            val input = sslSocket.inputStream
            val output = sslSocket.outputStream

            ProtoWire.writeDelimited(output, PairingMessages.buildPairingRequest(CLIENT_NAME, SERVICE_NAME))
            when (val step1 = nextMessage(input)) {
                is PairingMessages.Incoming.PairingRequestAck -> logI("PairingRequestAck recebido")
                is PairingMessages.Incoming.Error -> return@withContext errorResult(step1.status)
                else -> return@withContext protocolError("iniciar o pareamento")
            }

            ProtoWire.writeDelimited(output, PairingMessages.buildOptions())
            when (val step2 = nextMessage(input)) {
                is PairingMessages.Incoming.Options -> logI("Options recebido")
                is PairingMessages.Incoming.Error -> return@withContext errorResult(step2.status)
                else -> return@withContext protocolError("negociar as opções de pareamento")
            }

            ProtoWire.writeDelimited(output, PairingMessages.buildConfiguration())
            when (val step3 = nextMessage(input)) {
                is PairingMessages.Incoming.ConfigurationAck ->
                    logI("ConfigurationAck recebido; a TV deve estar exibindo o PIN agora")
                is PairingMessages.Incoming.Error -> return@withContext errorResult(step3.status)
                else -> return@withContext protocolError("confirmar a configuração de pareamento")
            }

            session = Session(sslSocket, input, output, clientKey, serverKey)
            socket = null // ownership transferred to `session`; don't close it in `finally` below
            ProtocolResult(true, "AWAITING_PIN", "Confirme o PIN exibido na TV.")
        } catch (e: SocketTimeoutException) {
            logW("Timeout durante pareamento com $host:$port: ${e.message}")
            ProtocolResult(false, "TIMEOUT", "A TV não respondeu a tempo.")
        } catch (e: SSLException) {
            logE("Erro TLS durante pareamento com $host:$port", e)
            ProtocolResult(false, "TLS_ERROR", "Falha na conexão segura com a TV.")
        } catch (e: IOException) {
            logE("Erro de rede durante pareamento com $host:$port", e)
            ProtocolResult(false, "CONNECTION_ERROR", "Não foi possível conectar à TV.")
        } catch (e: Exception) {
            logE("Erro inesperado durante pareamento com $host:$port", e)
            ProtocolResult(false, "PAIRING_ERROR", "Falha inesperada durante o pareamento.")
        } finally {
            try {
                socket?.close()
            } catch (_: IOException) {
                // Best-effort cleanup; nothing more to do if closing itself fails.
            }
        }
    }

    /** Runs steps 7-8 against the socket opened by [beginPairing], then always closes it. */
    suspend fun submitPin(pin: String): ProtocolResult = withContext(Dispatchers.IO) {
        val active = session
            ?: return@withContext ProtocolResult(false, "NO_PAIRING_SESSION", "Inicie o pareamento novamente.")
        if (pin.isBlank()) {
            return@withContext ProtocolResult(false, "PIN_EMPTY", "Informe o PIN exibido na TV.")
        }
        if (!HEX_PIN_REGEX.matches(pin)) {
            return@withContext ProtocolResult(false, "PIN_INVALID", "O PIN deve ter 6 dígitos hexadecimais.")
        }

        try {
            val secret = try {
                PairingMessages.computePinSecret(active.clientKey, active.serverKey, pin)
            } catch (e: PairingMessages.PinMismatchException) {
                logW("Checksum do PIN não confere; abortando sem enviar à TV")
                return@withContext ProtocolResult(false, "WRONG_PIN", "PIN incorreto.")
            } catch (e: IllegalArgumentException) {
                return@withContext ProtocolResult(false, "PIN_INVALID", "O PIN deve ter 6 dígitos hexadecimais.")
            }

            ProtoWire.writeDelimited(active.output, PairingMessages.buildSecret(secret))
            when (val step4 = nextMessage(active.input)) {
                is PairingMessages.Incoming.SecretAck -> {
                    logI("SecretAck recebido; pareamento concluído com sucesso")
                    ProtocolResult(true, "PAIRED", "TV pareada com sucesso.")
                }
                is PairingMessages.Incoming.Error -> errorResult(step4.status)
                else -> protocolError("concluir o pareamento")
            }
        } catch (e: SocketTimeoutException) {
            logW("Timeout ao enviar o PIN: ${e.message}")
            ProtocolResult(false, "TIMEOUT", "A TV não respondeu a tempo.")
        } catch (e: IOException) {
            logE("Erro de rede ao enviar o PIN", e)
            ProtocolResult(false, "CONNECTION_ERROR", "A conexão com a TV caiu.")
        } catch (e: Exception) {
            logE("Erro inesperado ao enviar o PIN", e)
            ProtocolResult(false, "PAIRING_ERROR", "Falha inesperada durante o pareamento.")
        } finally {
            cancelPairing()
        }
    }

    /** Closes any pairing session left open by [beginPairing] without completing it. */
    fun cancelPairing() {
        session?.socket?.let {
            try {
                it.close()
            } catch (_: IOException) {
                // Best-effort cleanup.
            }
        }
        session = null
    }

    private fun nextMessage(input: InputStream): PairingMessages.Incoming {
        val raw = ProtoWire.readDelimited(input)
            ?: throw IOException("Conexão encerrada pela TV antes da resposta esperada.")
        return PairingMessages.parse(raw)
    }

    private fun protocolError(duringStep: String): ProtocolResult =
        ProtocolResult(false, "PROTOCOL_ERROR", "Resposta inesperada da TV ao $duringStep.")

    private fun errorResult(status: Long): ProtocolResult = when (status) {
        PairingMessages.Status.BAD_SECRET -> ProtocolResult(false, "WRONG_PIN", "PIN incorreto.")
        PairingMessages.Status.BAD_CONFIGURATION ->
            ProtocolResult(false, "PROTOCOL_ERROR", "A TV rejeitou a configuração de pareamento.")
        else -> ProtocolResult(false, "PAIRING_REJECTED", "A TV rejeitou o pareamento (status $status).")
    }

    private companion object {
        private const val TAG = "PairingClient"
        private const val CLIENT_NAME = "MMPG Remote"
        private const val SERVICE_NAME = "mmpgremote"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val IO_TIMEOUT_MS = 15_000
        private val HEX_PIN_REGEX = Regex("^[0-9a-fA-F]{6}$")
    }
}
