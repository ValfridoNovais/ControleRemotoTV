package online.mmpg.remote.tv

/**
 * Plataforma/protocolo de controle por trás de um [TvDevice] - nunca inferida
 * pela marca do aparelho (ver PROMPT_NOVOS_RECURSO.md, seção 7): é sempre o
 * provider que descobriu o dispositivo quem determina isso, porque a mesma
 * marca pode usar sistemas diferentes (ex.: Hisense com VIDAA, Google TV,
 * Roku TV ou Fire TV a depender do modelo).
 */
enum class TvPlatform {
    ANDROID_TV,
    SAMSUNG_TIZEN,
    LG_WEBOS,
    ROKU,
    FIRE_TV,
    HISENSE_VIDAA,
    GENERIC_NETWORK,
    UNKNOWN
}
