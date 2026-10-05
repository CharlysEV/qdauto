package dev.qdauto.app.p2p

import java.net.Inet4Address
import java.net.NetworkInterface

/** IPv4 de la interfaz Wi-Fi Direct del móvil (`p2p-wlan0-0` en Samsung). */
object P2pNet {
    /**
     * Las de [interfaceName] (`WifiP2pGroup.getInterface()`); si no se conoce o no existe, las de cualquier interfaz
     * `p2p*`. Vacío mientras no haya IPv4 (como cliente, el DHCP del GO).
     */
    fun addresses(interfaceName: String?): List<Ipv4Net> {
        val named = interfaceName?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
        val candidates = if (named != null) {
            listOf(named)
        } else {
            runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
                .filter { it.name.startsWith("p2p") }
        }
        return candidates.flatMap { ni ->
            runCatching { ni.interfaceAddresses.toList() }.getOrDefault(emptyList())
                .filter { it.address is Inet4Address }
                .map { Ipv4Net(it.address.hostAddress ?: "?", it.networkPrefixLength.toInt()) }
        }
    }
}
