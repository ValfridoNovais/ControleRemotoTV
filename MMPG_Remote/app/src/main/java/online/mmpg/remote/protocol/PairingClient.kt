package online.mmpg.remote.protocol

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
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
 * This never fabricates success: every step requires a real response from
 * the TV over a real TLS socket, and any network/protocol/PIN failure is
 * surfaced as a specific [ProtocolResult] instead. There is no TV available
 * in this development environment to validate the handshake end-to-end
 * against real hardware; a `Socket`/`SSLSocket` connect attempt with nothing
 * listening will simply fail with a connection error, which is the correct,
 * honest behavior for this stage.
 *
 * The PIN is held in memory only for the duration of a single [pair] call
 * (as the plain `pin` parameter and the derived SHA-256 secret) and is never
 * logged or persisted; only high-level lifecycle events are logged.
 */
class PairingClient(private val certificateStore: CertificateStore) {

    suspend fun pair(host: String, port: Int, pin: String): ProtocolResult = withContext(Dispatchers.IO) {
        if (pin.isBlank()) {
            return@withContext ProtocolResult(false, "PIN_EMPTY", "Informe o PIN exibido na TV.")
        }
        if (!HEX_PIN_REGEX.matches(pin)) {
            return@withContext ProtocolResult(false, "PIN_INVALID", "O PIN deve ter 6 dígitos hexadecimais.")
        }

        var socket: Socket? = null
        try {
            Log.i(TAG, "Iniciando handshake TLS de pareamento com $host:$port")
            val clientCert = certificateStore.getOrCreateClientCertificate()

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(
                certificateStore.getKeyManagers(),
                arrayOf(AcceptAnyServerTrustManager),
                SecureRandom()
            )

            val plainSocket = Socket()
            plainSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val sslSocket = sslContext.socketFactory.createSocket(plainSocket, host, port, true) as SSLSocket
            sslSocket.soTimeout = IO_TIMEOUT_MS
            socket = sslSocket
            sslSocket.startHandshake()
            Log.i(TAG, "Handshake TLS concluído; iniciando troca de mensagens de pareamento")

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
                is PairingMessages.Incoming.PairingRequestAck -> Log.i(TAG, "PairingRequestAck recebido")
                is PairingMessages.Incoming.Error -> return@withContext errorResult(step1.status)
                else -> return@withContext protocolError("iniciar o pareamento")
            }

            ProtoWire.writeDelimited(output, PairingMessages.buildOptions())
            when (val step2 = nextMessage(input)) {
                is PairingMessages.Incoming.Options -> Log.i(TAG, "Options recebido")
                is PairingMessages.Incoming.Error -> return@withContext errorResult(step2.status)
                else -> return@withContext protocolError("negociar as opções de pareamento")
            }

            ProtoWire.writeDelimited(output, PairingMessages.buildConfiguration())
            when (val step3 = nextMessage(input)) {
                is PairingMessages.Incoming.ConfigurationAck ->
                    Log.i(TAG, "ConfigurationAck recebido; a TV deve estar exibindo o PIN agora")
                is PairingMessages.Incoming.Error -> return@withContext errorResult(step3.status)
                else -> return@withContext protocolError("confirmar a configuração de pareamento")
            }

            val secret = try {
                PairingMessages.computePinSecret(clientKey, serverKey, pin)
            } catch (e: PairingMessages.PinMismatchException) {
                Log.w(TAG, "Checksum do PIN não confere; abortando sem enviar à TV")
                return@withContext ProtocolResult(false, "WRONG_PIN", "PIN incorreto.")
            } catch (e: IllegalArgumentException) {
                return@withContext ProtocolResult(false, "PIN_INVALID", "O PIN deve ter 6 dígitos hexadecimais.")
            }

            ProtoWire.writeDelimited(output, PairingMessages.buildSecret(secret))
            when (val step4 = nextMessage(input)) {
                is PairingMessages.Incoming.SecretAck -> {
                    Log.i(TAG, "SecretAck recebido; pareamento concluído com sucesso")
                    ProtocolResult(true, "PAIRED", "TV pareada com sucesso.")
                }
                is PairingMessages.Incoming.Error -> errorResult(step4.status)
                else -> protocolError("concluir o pareamento")
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Timeout durante pareamento com $host:$port: ${e.message}")
            ProtocolResult(false, "TIMEOUT", "A TV não respondeu a tempo.")
        } catch (e: SSLException) {
            Log.w(TAG, "Erro TLS durante pareamento com $host:$port: ${e.message}")
            ProtocolResult(false, "TLS_ERROR", "Falha na conexão segura com a TV.")
        } catch (e: IOException) {
            Log.w(TAG, "Erro de rede durante pareamento com $host:$port: ${e.message}")
            ProtocolResult(false, "CONNECTION_ERROR", "Não foi possível conectar à TV.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro inesperado durante pareamento com $host:$port", e)
            ProtocolResult(false, "PAIRING_ERROR", "Falha inesperada durante o pareamento.")
        } finally {
            try {
                socket?.close()
            } catch (_: IOException) {
                // Best-effort cleanup; nothing more to do if closing itself fails.
            }
        }
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
