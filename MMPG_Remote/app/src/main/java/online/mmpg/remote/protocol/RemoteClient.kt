package online.mmpg.remote.protocol

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import online.mmpg.remote.BuildConfig
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * Android TV Remote Protocol v2 remote-control client (TCP/TLS, port 6466).
 *
 * Reuses this app's already-paired Keystore-backed client identity
 * ([CertificateStore.getKeyManagers]) for the mutual-TLS handshake - the same
 * identity presented during pairing on port 6467 - which is how the TV
 * recognizes this client without repeating the PIN challenge. Server
 * certificate validation uses the same trust-on-first-use
 * [AcceptAnyServerTrustManager] as pairing, for the same protocol reason (the
 * TV presents a self-signed certificate; see that class's doc).
 *
 * Message shapes (see [RemoteMessages]) and the handshake/heartbeat/key-inject
 * sequence below were cross-checked against tronikos/androidtvremote2
 * (Apache-2.0, `remote.py`) and louis49/androidtv-remote (MIT,
 * `RemoteManager.js`) - see THIRD_PARTY_NOTICES.md for exactly which files
 * were consulted:
 *
 *   1. Mutual TLS handshake on 6466 (this app's paired client identity).
 *   2. TV -> client : RemoteConfigure (its supported feature bitmask + device info)
 *      client -> TV : RemoteConfigure (features = [RemoteMessages.Feature.SUPPORTED]
 *                     AND the TV's bitmask, plus this phone's device info)
 *   3. TV -> client : RemoteSetActive
 *      client -> TV : RemoteSetActive (echoes the negotiated feature bitmask)
 *      -> channel is now considered ready; [connect] returns ok=true only after
 *         this exchange actually completes over the real socket.
 *   4. For as long as the connection is open, the TV periodically sends
 *      RemotePingRequest{val1}; this client replies RemotePingResponse{val1}
 *      from a dedicated read coroutine (see [readLoop]). RemoteStart is also
 *      received (TV screen on/off) and logged, but is not required to
 *      consider the channel ready.
 *   5. To press a key, client sends a single RemoteKeyInject message with
 *      direction=SHORT - a normal, instantaneous tap, matching both
 *      reference clients' default behavior (tronikos'
 *      `send_key_command(key_code, direction=RemoteDirection.SHORT)` and
 *      louis49's `RemoteManager.sendKey`/`sendPower`, both defaulting to
 *      SHORT for an ordinary press). See [RemoteMessages.Direction] doc for
 *      why START_LONG+END_LONG back-to-back is a different gesture (an
 *      instant press-and-hold, not a tap) and is not used here. All 14
 *      [RemoteProtocol.supportedKeys] entries are now wired to real keycodes
 *      via [RemoteMessages.keyCodeForCommand] (Prompt 5 completed the 9 keys
 *      beyond the D-pad/ENTER: HOME/BACK/POWER/VOLUME_UP/VOLUME_DOWN/MUTE/
 *      CHANNEL_UP/CHANNEL_DOWN/PLAY_PAUSE). [keyCodeForCommand] returning
 *      null is kept as a defensive NOT_IMPLEMENTED fallback below in case a
 *      future key is added to `supportedKeys` without a mapping - it is not
 *      expected to trigger for any of the 14 keys today.
 *
 * This never fabricates success: [connect] only returns ok=true after the
 * RemoteConfigure/RemoteSetActive exchange actually completes over a real
 * socket, and [sendKey] only returns ok=true after both key-inject messages
 * are actually written to that socket. There is no TV available in this
 * development environment to validate the handshake end-to-end against real
 * hardware; connecting to a host with nothing listening on 6466 fails
 * honestly with a connection error.
 *
 * Lifecycle / reconnection: at most one TLS socket and one read coroutine are
 * ever live at a time. [connect] always calls [close] first, so a second
 * connect() (e.g. the user picks another TV, or retries) tears down any
 * previous socket/job before opening a new one. [close] cancels the read job
 * and closes the socket synchronously. If the TV drops the connection on its
 * own (read failure/EOF in [readLoop]), [teardown] releases the same state,
 * guarded by object identity so a stale, already-superseded connection's
 * cleanup can never clobber a newer, already-established one.
 */
class RemoteClient(private val certificateStore: CertificateStore) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val writeLock = Any()

    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    private var readJob: Job? = null

    @Volatile private var connectedHost: String? = null
    @Volatile private var negotiatedFeatures: Int = RemoteMessages.Feature.SUPPORTED

    suspend fun connect(host: String): ProtocolResult = withContext(Dispatchers.IO) {
        close() // reconnection safety: never leave a previous socket/job behind.
        var pendingSocket: Socket? = null
        try {
            Log.i(TAG, "Conectando ao canal remoto ${RemoteProtocol.DEFAULT_REMOTE_PORT} de $host")
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(
                certificateStore.getKeyManagers(),
                arrayOf(AcceptAnyServerTrustManager),
                SecureRandom()
            )

            val plainSocket = Socket()
            plainSocket.connect(
                InetSocketAddress(host, RemoteProtocol.DEFAULT_REMOTE_PORT),
                CONNECT_TIMEOUT_MS
            )
            pendingSocket = plainSocket
            val sslSocket = sslContext.socketFactory.createSocket(
                plainSocket, host, RemoteProtocol.DEFAULT_REMOTE_PORT, true
            ) as SSLSocket
            pendingSocket = sslSocket
            sslSocket.soTimeout = HANDSHAKE_TIMEOUT_MS
            sslSocket.startHandshake()
            Log.i(TAG, "Handshake TLS do canal remoto concluído com $host")

            val input = sslSocket.inputStream
            val out = sslSocket.outputStream
            performInitHandshake(input, out)
            sslSocket.soTimeout = IDLE_TIMEOUT_MS

            synchronized(stateLock) {
                socket = sslSocket
                output = out
                connectedHost = host
                readJob = ioScope.launch { readLoop(sslSocket, input) }
            }
            Log.i(TAG, "Canal remoto pronto para enviar comandos a $host")
            ProtocolResult(true, "CONNECTED", "Canal remoto conectado.")
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Timeout ao inicializar canal remoto com $host: ${e.message}")
            closeQuietly(pendingSocket)
            ProtocolResult(false, "TIMEOUT", "A TV não respondeu a tempo no canal remoto.")
        } catch (e: SSLException) {
            Log.w(TAG, "Erro TLS no canal remoto com $host: ${e.message}")
            closeQuietly(pendingSocket)
            ProtocolResult(false, "TLS_ERROR", "Falha na conexão segura com a TV.")
        } catch (e: IOException) {
            Log.w(TAG, "Erro de rede no canal remoto com $host: ${e.message}")
            closeQuietly(pendingSocket)
            ProtocolResult(false, "CONNECTION_ERROR", "Não foi possível conectar ao canal remoto da TV.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro inesperado ao conectar ao canal remoto de $host", e)
            closeQuietly(pendingSocket)
            ProtocolResult(false, "REMOTE_ERROR", "Falha inesperada ao conectar ao canal remoto.")
        }
    }

    /**
     * Sends one key press as a single `RemoteKeyInject{direction=SHORT}`
     * message (see class doc, point 5). Never fabricates success: an unknown
     * command is rejected before touching the socket (`UNKNOWN_KEY`), and a
     * connected-but-unmapped command would be rejected the same way
     * (`NOT_IMPLEMENTED`) rather than silently sending a wrong/garbage
     * keycode - though with all 14 [RemoteProtocol.supportedKeys] now mapped
     * by [RemoteMessages.keyCodeForCommand], that second branch is a
     * defensive fallback only, not expected to trigger. `ok=true` is
     * returned only after [writeMessage] actually completes on the live
     * socket.
     */
    suspend fun sendKey(key: String): ProtocolResult = withContext(Dispatchers.IO) {
        if (key !in RemoteProtocol.supportedKeys) {
            return@withContext ProtocolResult(false, "UNKNOWN_KEY", "Comando não suportado: $key")
        }
        val keyCode = RemoteMessages.keyCodeForCommand(key)
        if (keyCode == null) {
            // Defensive fallback: every key in RemoteProtocol.supportedKeys now
            // has a real mapping (see RemoteMessages.keyCodeForCommand), so this
            // should never trigger for a currently supported key. Kept so that
            // if a future key is ever added to supportedKeys without a mapping,
            // it fails with an explicit, honest error instead of silently
            // sending a bogus keycode or reporting a fake success.
            return@withContext ProtocolResult(
                false,
                "NOT_IMPLEMENTED",
                "Comando '$key' ainda não implementado neste canal."
            )
        }
        val out = output
        if (connectedHost == null || out == null) {
            return@withContext ProtocolResult(false, "NOT_CONNECTED", "Conecte-se à TV antes de enviar comandos.")
        }
        try {
            // A normal tap is a single SHORT key-inject message - see class
            // doc and RemoteMessages.Direction for why START_LONG+END_LONG is
            // a different (press-and-hold) gesture and not used here.
            writeMessage(out, RemoteMessages.buildKeyInject(keyCode, RemoteMessages.Direction.SHORT))
            Log.d(TAG, "Tecla $key enviada para $connectedHost")
            ProtocolResult(true, "KEY_SENT", "Comando enviado.")
        } catch (e: IOException) {
            Log.w(TAG, "Falha ao enviar tecla $key: ${e.message}")
            teardown(socket) // the socket is presumably dead; drop it instead of leaking a half-broken connection.
            ProtocolResult(false, "CONNECTION_ERROR", "Conexão com a TV perdida ao enviar o comando.")
        }
    }

    /**
     * Closes the current connection, if any: cancels the read/heartbeat
     * coroutine and closes the socket synchronously so no socket is ever
     * left open without a reader. Safe to call multiple times and safe to
     * call when nothing is connected.
     */
    fun close() {
        val current: Socket?
        synchronized(stateLock) {
            current = socket
            readJob?.cancel()
            readJob = null
            socket = null
            output = null
            connectedHost = null
        }
        if (current != null) {
            closeQuietly(current)
            Log.i(TAG, "Canal remoto encerrado")
        }
    }

    /**
     * Runs for the lifetime of one connection: blocks reading delimited
     * RemoteMessage frames and reacts to them (heartbeat replies, logging).
     * Ends on EOF, on any IOException (including the idle [IDLE_TIMEOUT_MS]
     * socket timeout if the TV goes silent), or on cancellation from [close].
     * Always tears down its own connection state on the way out.
     */
    private fun readLoop(ownedSocket: SSLSocket, input: InputStream) {
        try {
            while (true) {
                val raw = ProtoWire.readDelimited(input) ?: break
                handleIncoming(raw)
            }
            Log.i(TAG, "TV encerrou a conexão do canal remoto (EOF)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Conexão com o canal remoto encerrada: ${e.message}")
        } finally {
            teardown(ownedSocket)
        }
    }

    /**
     * Releases connection state owned by [ownedSocket], but only if it is
     * still the currently active socket. Guarded by identity so a stale
     * connection's own cleanup (e.g. its blocked read finally unblocking
     * after [close] already replaced it with a fresh connection) can never
     * wipe out state that belongs to a newer, already-established
     * connection - this is what keeps reconnection from leaking sockets
     * without also creating cross-connection races.
     */
    private fun teardown(ownedSocket: Socket?) {
        if (ownedSocket == null) return
        synchronized(stateLock) {
            if (socket !== ownedSocket) return@synchronized
            socket = null
            output = null
            connectedHost = null
            readJob = null
        }
        closeQuietly(ownedSocket)
    }

    private fun handleIncoming(raw: ByteArray) {
        val out = output ?: return
        when (val message = RemoteMessages.parse(raw)) {
            is RemoteMessages.Incoming.PingRequest -> {
                Log.d(TAG, "RemotePingRequest recebido; respondendo heartbeat")
                writeMessage(out, RemoteMessages.buildPingResponse(message.val1))
            }
            is RemoteMessages.Incoming.Configure -> {
                val negotiated = RemoteMessages.Feature.SUPPORTED and message.code1.toInt()
                negotiatedFeatures = negotiated
                writeMessage(out, buildOwnConfigure(negotiated))
            }
            RemoteMessages.Incoming.SetActive ->
                writeMessage(out, RemoteMessages.buildSetActive(negotiatedFeatures))
            is RemoteMessages.Incoming.Start ->
                Log.i(TAG, "RemoteStart recebido (started=${message.started})")
            RemoteMessages.Incoming.Unknown ->
                Log.d(TAG, "Mensagem do canal remoto ignorada (tipo não tratado neste incremento)")
        }
    }

    /**
     * Drives the RemoteConfigure/RemoteSetActive handshake to completion
     * before [connect] considers the channel ready. Any RemotePingRequest
     * arriving during this phase is answered immediately, same as after
     * the handshake, since some devices may start pinging before both
     * exchanges finish.
     */
    private fun performInitHandshake(input: InputStream, out: OutputStream) {
        var configured = false
        var activeAcked = false
        while (!(configured && activeAcked)) {
            val raw = ProtoWire.readDelimited(input)
                ?: throw IOException("A TV encerrou a conexão do canal remoto durante a inicialização.")
            when (val message = RemoteMessages.parse(raw)) {
                is RemoteMessages.Incoming.Configure -> {
                    val negotiated = RemoteMessages.Feature.SUPPORTED and message.code1.toInt()
                    negotiatedFeatures = negotiated
                    writeMessage(out, buildOwnConfigure(negotiated))
                    configured = true
                    Log.i(TAG, "RemoteConfigure trocado; features negociadas=$negotiated")
                }
                RemoteMessages.Incoming.SetActive -> {
                    writeMessage(out, RemoteMessages.buildSetActive(negotiatedFeatures))
                    activeAcked = true
                    Log.i(TAG, "RemoteSetActive confirmado; canal remoto pronto para comandos")
                }
                is RemoteMessages.Incoming.PingRequest ->
                    writeMessage(out, RemoteMessages.buildPingResponse(message.val1))
                is RemoteMessages.Incoming.Start ->
                    Log.i(TAG, "RemoteStart recebido durante inicialização (started=${message.started})")
                RemoteMessages.Incoming.Unknown ->
                    Log.d(TAG, "Mensagem não tratada recebida durante inicialização do canal remoto")
            }
        }
    }

    private fun buildOwnConfigure(code1: Int): ByteArray = RemoteMessages.buildConfigure(
        code1 = code1,
        model = Build.MODEL ?: "unknown",
        vendor = Build.MANUFACTURER ?: "unknown",
        packageName = BuildConfig.APPLICATION_ID,
        appVersion = BuildConfig.VERSION_NAME
    )

    private fun writeMessage(out: OutputStream, message: ByteArray) {
        synchronized(writeLock) {
            ProtoWire.writeDelimited(out, message)
        }
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
            // Best-effort cleanup; nothing more to do if closing itself fails.
        }
    }

    private companion object {
        private const val TAG = "RemoteClient"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val HANDSHAKE_TIMEOUT_MS = 15_000

        // TV pings roughly every 5s while idle (per tronikos/androidtvremote2);
        // 20s gives ample margin before we treat the connection as dead if it
        // goes silent, mirroring that reference client's own 16s idle guard.
        private const val IDLE_TIMEOUT_MS = 20_000
    }
}
