package online.mmpg.remote.protocol

/**
 * Constantes compartilhadas do Android TV Remote Protocol v2 usadas por toda a
 * camada `protocol/`.
 *
 * [PairingClient] (porta 6467) e [RemoteClient] (porta 6466) já implementam o
 * handshake TLS + protobuf real descrito em AGENTS.md/THIRD_PARTY_NOTICES.md -
 * nenhum dos dois é mais um stub. O que continua pendente, e é rastreado em
 * `TESTE_MANUAL_TV_REAL.md`, é a validação ponta a ponta contra uma TV real
 * (SEMP TCL 32S615); sem isso o app nunca declara um pareamento/conexão como
 * bem-sucedido artificialmente - cada [ProtocolResult] só reporta `ok=true`
 * após uma resposta real da TV no socket.
 */
object RemoteProtocol {
    const val DEFAULT_PAIRING_PORT = 6467
    const val DEFAULT_REMOTE_PORT = 6466

    val supportedKeys = setOf(
        "UP", "DOWN", "LEFT", "RIGHT", "ENTER",
        "HOME", "BACK", "POWER",
        "VOLUME_UP", "VOLUME_DOWN", "MUTE",
        "CHANNEL_UP", "CHANNEL_DOWN", "PLAY_PAUSE"
    )
}
