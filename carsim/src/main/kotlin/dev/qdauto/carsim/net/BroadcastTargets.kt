package dev.qdauto.carsim.net

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/** Un destino del `Connect_Broadcast`. */
data class BroadcastTarget(val address: InetAddress, val label: String) {
    override fun toString(): String = "${address.hostAddress} ($label)"
}

/**
 * Destinos del broadcast. En Windows el broadcast limitado (255.255.255.255) sale solo por una interfaz, que puede
 * no ser la Wi-Fi (adaptadores de Hyper-V, VPN...), así que por defecto se manda también al broadcast de subred
 * de cada interfaz IPv4 activa.
 */
object BroadcastTargets {
    const val KEYWORD = "broadcast"
    val LIMITED: InetAddress = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))

    /** 'broadcast' se expande a [defaults]; el resto son IP o nombres ya validados. Sin duplicados. */
    fun resolve(specs: List<String>): List<BroadcastTarget> {
        val out = ArrayList<BroadcastTarget>()
        for (spec in specs.ifEmpty { listOf(KEYWORD) }) {
            if (spec.equals(KEYWORD, ignoreCase = true)) {
                out += defaults()
            } else {
                val address = InetAddress.getByName(spec)
                out += BroadcastTarget(address, if (address == LIMITED) "broadcast limitado" else "indicada con --target")
            }
        }
        return out.distinctBy { it.address }
    }

    /** 255.255.255.255 y el broadcast de cada interfaz IPv4 activa que no sea loopback. */
    fun defaults(): List<BroadcastTarget> {
        val out = arrayListOf(BroadcastTarget(LIMITED, "broadcast limitado"))
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: SocketException) {
            emptyList()
        }
        for (ni in interfaces) {
            val usable = try {
                ni.isUp && !ni.isLoopback
            } catch (_: SocketException) {
                false
            }
            if (!usable) continue
            for (ia in ni.interfaceAddresses) {
                val address = ia.address as? Inet4Address ?: continue
                val broadcast = ia.broadcast ?: continue
                out += BroadcastTarget(broadcast, "${ni.displayName}, ${address.hostAddress}/${ia.networkPrefixLength}")
            }
        }
        return out.distinctBy { it.address }
    }
}
