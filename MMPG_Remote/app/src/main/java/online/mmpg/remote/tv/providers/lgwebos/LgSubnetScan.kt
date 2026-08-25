package online.mmpg.remote.tv.providers.lgwebos

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import online.mmpg.remote.tv.providers.net.LocalSubnet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Varredura de sub-rede como alternativa ao SSDP para TVs LG webOS - mesmo
 * problema documentado para Samsung, mais o consenso da própria comunidade
 * lgtv2/Home Assistant: "SSDP primeiro, depois cai para uma varredura
 * limitada da sub-rede pela porta WebSocket do webOS" (ver
 * docs/providers/lg-webos.md).
 *
 * Ao contrário da Samsung (que tem um GET HTTP não autenticado para
 * identificar o aparelho - ver [online.mmpg.remote.tv.providers.samsung.SamsungInfoClient]),
 * a LG não tem um endpoint equivalente. A sonda aqui é abrir o próprio
 * WebSocket ([LgSsapClient.connect], com um timeout curto só para
 * sondagem) sem nunca mandar "register" - como "register" é o único passo
 * que dispara o prompt de confirmação na tela da TV, essa sondagem não
 * incomoda o usuário em TVs que ele nem selecionou ainda.
 */
object LgSubnetScan {
    suspend fun scan(
        concurrency: Int = 32,
        probeTimeoutMs: Long = 400,
        onDiag: (String) -> Unit = {},
        onFound: suspend (host: String) -> Unit
    ) {
        val base = LocalSubnet.slash24Base() ?: return
        val semaphore = Semaphore(concurrency)
        val attempted = AtomicInteger(0)
        val found = AtomicInteger(0)
        coroutineScope {
            (1..254).map { last ->
                async {
                    semaphore.withPermit {
                        val host = "$base.$last"
                        // Sondagem silenciosa de propósito: 254 hosts x até 2
                        // portas cada gerariam ~500 linhas de log por
                        // varredura, afogando qualquer diagnóstico real (de
                        // pareamento/conexão de outro provider) que aconteça
                        // ao mesmo tempo. Só o resumo no final é logado.
                        val probe = LgSsapClient(onDiag = {})
                        val open = try {
                            probe.connect(host, openTimeoutMs = probeTimeoutMs)
                        } finally {
                            probe.close()
                        }
                        attempted.incrementAndGet()
                        if (open) { found.incrementAndGet(); onFound(host) }
                    }
                }
            }.awaitAll()
        }
        onDiag("LG webOS: varredura de sub-rede concluída (${attempted.get()} endereço(s) testado(s), ${found.get()} responderam)")
    }
}
