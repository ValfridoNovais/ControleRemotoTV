package online.mmpg.remote.tv.providers.samsung

import online.mmpg.remote.tv.providers.net.LocalSubnet
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Envia o pacote mágico Wake-on-LAN (6 bytes `0xFF` + 16 repetições do MAC)
 * por broadcast UDP - o único jeito de "ligar" uma TV cujo WebSocket, por
 * definição, não está ouvindo porque ela está desligada. Só funciona se a
 * TV tiver Wake-on-LAN/Wi-Fi habilitado nas configurações (padrão em
 * modelos Samsung recentes, mas não garantido em todos).
 *
 * Manda para **dois** endereços de broadcast, não só `255.255.255.255`: em
 * Wi-Fi (ao contrário de Ethernet), alguns stacks Android/roteadores não
 * roteiam corretamente o broadcast limitado `255.255.255.255` - o broadcast
 * dirigido da própria sub-rede (ex.: `192.168.1.255` para uma rede /24,
 * calculado via [LocalSubnet]) costuma ser bem mais confiável nessas
 * condições. Mandar os dois é barato (mais um datagrama de ~100 bytes) e
 * cobre ambos os casos sem precisar saber de antemão qual vai funcionar.
 */
object WakeOnLan {
    fun send(mac: String, onDiag: (String) -> Unit = {}): Boolean {
        val macBytes = try {
            mac.split(":", "-")
                .map { it.toInt(16).toByte() }
                .also { require(it.size == 6) { "endereço MAC precisa ter 6 bytes" } }
        } catch (e: Exception) {
            onDiag("Wake-on-LAN: endereço MAC inválido ($mac)")
            return false
        }

        val packet = ByteArray(6 + 16 * 6).apply {
            for (i in 0 until 6) this[i] = 0xFF.toByte()
            for (rep in 0 until 16) {
                macBytes.forEachIndexed { j, b -> this[6 + rep * 6 + j] = b }
            }
        }

        val targets = buildList {
            add(GLOBAL_BROADCAST_ADDRESS)
            LocalSubnet.slash24Base()?.let { add("$it.255") }
        }.distinct()

        var sentAny = false
        for (target in targets) {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(target), WOL_PORT))
                }
                onDiag("Wake-on-LAN: pacote mágico enviado para $mac via broadcast $target")
                sentAny = true
            } catch (e: Exception) {
                onDiag("Wake-on-LAN: falha ao enviar via $target (${e.javaClass.simpleName})")
            }
        }
        return sentAny
    }

    private const val GLOBAL_BROADCAST_ADDRESS = "255.255.255.255"
    private const val WOL_PORT = 9
}
