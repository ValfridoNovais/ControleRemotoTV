package online.mmpg.remote.tv

/**
 * Modelo de dispositivo compartilhado entre todos os providers - distinto de
 * [online.mmpg.remote.discovery.TvDevice] (resultado bruto da resolução NSD,
 * usado só internamente por `TvDiscovery`/`AndroidTvProvider`).
 *
 * [id] é a chave estável entre re-descobertas - nunca [host]: o IP pode
 * mudar por DHCP, então nunca serve como identificador definitivo (ver
 * PROMPT_NOVOS_RECURSO.md, seção 14). [providerId] é o que permite ao
 * [TvManager] rotear um comando para o [TvProvider] certo sem nunca inferir
 * o protocolo pela marca (seção 7).
 */
data class TvDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int?,
    val manufacturer: String?,
    val model: String?,
    val platform: TvPlatform,
    val providerId: String,
    val services: List<String>,
    val capabilities: Set<TvCapability>
)
