package online.mmpg.remote.tv

/**
 * Recurso que um [TvProvider] pode ou não suportar para um dado dispositivo.
 * A UI deve esconder/desabilitar comandos cuja capability o provider atual
 * não declara em [TvProvider.supports] - nunca supor que toda TV aceita
 * todos os comandos (ver PROMPT_NOVOS_RECURSO.md, seção 10).
 */
enum class TvCapability {
    POWER,
    DPAD,
    HOME,
    BACK,

    VOLUME,
    MUTE,
    CHANNEL,

    PLAYBACK,

    INPUT,

    TEXT_INPUT,

    APP_LIST,
    APP_LAUNCH,

    CURRENT_APP,

    POWER_STATE
}
