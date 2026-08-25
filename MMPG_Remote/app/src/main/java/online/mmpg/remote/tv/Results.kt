package online.mmpg.remote.tv

import online.mmpg.remote.protocol.ProtocolResult

/**
 * PairingResult/ConnectionResult/CommandResult reaproveitam
 * [ProtocolResult] - mesmo formato `{ok, code, message}` já usado em toda a
 * camada `protocol/` e serializado direto para JSON no boundary da WebView
 * (ver [online.mmpg.remote.TvBridge]). Nomes distintos só deixam a
 * assinatura de [TvProvider] legível, sem duplicar o tipo nem reescrever a
 * lógica de protocolo já validada contra hardware real.
 */
typealias PairingResult = ProtocolResult
typealias ConnectionResult = ProtocolResult
typealias CommandResult = ProtocolResult
