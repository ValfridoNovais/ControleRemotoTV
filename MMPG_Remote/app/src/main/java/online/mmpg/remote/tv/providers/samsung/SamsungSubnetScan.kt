package online.mmpg.remote.tv.providers.samsung

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import online.mmpg.remote.tv.providers.net.LocalSubnet

/**
 * Varredura de sub-rede como alternativa ao SSDP para TVs Samsung - o SSDP
 * delas não responde de forma confiável em vários modelos/redes, um
 * problema conhecido e documentado na comunidade (ver
 * docs/providers/samsung-tizen.md e THIRD_PARTY_NOTICES.md), não uma
 * peculiaridade de um aparelho específico. Sonda `http://ip:8001/api/v2/`
 * ([SamsungInfoClient]) em cada endereço da sub-rede /24 do próprio
 * celular, em paralelo com um limite de concorrência.
 *
 * Só roda para sub-redes /24 ou menores (a esmagadora maioria das redes
 * domésticas) - deliberadamente não varre faixas maiores, para não
 * bombardear uma rede grande com centenas/milhares de tentativas de conexão.
 */
object SamsungSubnetScan {
    suspend fun scan(
        concurrency: Int = 32,
        timeoutMs: Int = 400,
        onFound: suspend (host: String, info: SamsungInfoClient.Info) -> Unit
    ) {
        val base = LocalSubnet.slash24Base() ?: return
        val semaphore = Semaphore(concurrency)
        coroutineScope {
            (1..254).map { last ->
                async {
                    semaphore.withPermit {
                        val host = "$base.$last"
                        val info = SamsungInfoClient.fetch(host, timeoutMs)
                        if (info != null) onFound(host, info)
                    }
                }
            }.awaitAll()
        }
    }
}
