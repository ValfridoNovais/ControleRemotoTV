package online.mmpg.remote.tv.providers.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Endereço-base "a.b.c" da sub-rede /24 local do celular - usado por
 * qualquer varredura de sub-rede que sirva de alternativa ao SSDP (LG e
 * Samsung até agora: SSDP se mostrou pouco confiável para os dois, ver
 * docs/providers/lg-webos.md e docs/providers/samsung-tizen.md). Extraída
 * de uma versão específica da Samsung depois que a LG precisou exatamente
 * da mesma coisa - mesmo padrão já usado para generalizar [online.mmpg.remote.tv.providers.ssdp.SsdpDiscovery].
 */
object LocalSubnet {
    /** "192.168.1" a partir do primeiro endereço IPv4 privado /24 encontrado, ou `null` se a rede não for /24. */
    fun slash24Base(): String? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList()
        } catch (e: Exception) {
            null
        } ?: return null

        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.interfaceAddresses) {
                val ip = addr.address
                if (ip !is Inet4Address || ip.isLoopbackAddress) continue
                if (addr.networkPrefixLength.toInt() != 24) continue
                val parts = ip.hostAddress?.split(".") ?: continue
                if (parts.size != 4) continue
                return "${parts[0]}.${parts[1]}.${parts[2]}"
            }
        }
        return null
    }
}
