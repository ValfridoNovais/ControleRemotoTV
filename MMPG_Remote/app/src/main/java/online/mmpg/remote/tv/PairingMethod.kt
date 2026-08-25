package online.mmpg.remote.tv

/**
 * Como um [TvProvider] pareia com o dispositivo - a UI se adapta a este
 * valor em vez de assumir um único fluxo (ver PROMPT_NOVOS_RECURSO.md, seção
 * 12). O Android TV usa [PIN]: a TV exibe um código de 6 dígitos hex depois
 * que o handshake começa (ver [online.mmpg.remote.protocol.PairingClient]).
 */
enum class PairingMethod {
    NONE,
    PIN,
    TV_CONFIRMATION,
    TOKEN
}
