@file:Suppress("DEPRECATION") // NetworkInfo: el extra de CONNECTION_CHANGED sigue llegando y se registra.

package dev.qdauto.app.p2p

import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.IntentCompat

/** Traduce los objetos de `android.net.wifi.p2p` al modelo de la app y a texto de una línea para el log. */
internal object P2pDescribe {
    fun peer(d: WifiP2pDevice): P2pPeer = P2pPeer(
        address = d.deviceAddress.orEmpty(),
        name = d.deviceName.orEmpty(),
        status = PeerStatus.of(d.status),
        isGroupOwner = d.isGroupOwner,
        primaryType = d.primaryDeviceType,
        secondaryType = d.secondaryDeviceType,
        wps = listOfNotNull(
            "PBC".takeIf { d.wpsPbcSupported() },
            "PIN-pantalla".takeIf { d.wpsDisplaySupported() },
            "PIN-teclado".takeIf { d.wpsKeypadSupported() },
        ).joinToString("/"),
        serviceDiscoveryCapable = d.isServiceDiscoveryCapable,
        wfd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) d.wfdInfo?.let { oneLine(it.toString()) } else null,
        raw = device(d),
    )

    fun peers(list: WifiP2pDeviceList?): List<P2pPeer> = list?.deviceList.orEmpty().map(::peer)

    /** `toString()` del framework: nombre, MAC, tipos, WPS, capacidades, estado y WFD. */
    fun device(d: WifiP2pDevice?): String = if (d == null) "null" else oneLine(d.toString()) + " · isGroupOwner=${d.isGroupOwner}"

    fun info(i: WifiP2pInfo?): String = if (i == null) {
        "WifiP2pInfo null"
    } else {
        "groupFormed=${i.groupFormed} isGroupOwner=${i.isGroupOwner} groupOwnerAddress=${i.groupOwnerAddress?.hostAddress}"
    }

    /** Todo menos la passphrase. */
    fun group(g: WifiP2pGroup?): String {
        if (g == null) return "WifiP2pGroup null"
        val parts = mutableListOf(
            "red «${g.networkName}»",
            "interfaz ${g.`interface`}",
            "${g.frequency} MHz",
            "móvil GO ${g.isGroupOwner}",
            "GO ${g.owner?.deviceName} (${g.owner?.deviceAddress})",
            "clientes [" + g.clientList.orEmpty().joinToString { "${it.deviceName} (${it.deviceAddress})" } + "]",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) parts += "netId ${g.networkId}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            parts += "seguridad ${g.securityType}"
            parts += "BSSID del GO ${g.groupOwnerBssid}"
        }
        return parts.joinToString(" · ")
    }

    fun networkInfo(intent: Intent): NetworkInfo? =
        IntentCompat.getParcelableExtra(intent, WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)

    fun network(n: NetworkInfo?): String =
        if (n == null) "NetworkInfo null" else "NetworkInfo ${n.detailedState} (${n.state}) extra=${n.extraInfo} motivo=${n.reason}"

    fun isFailed(n: NetworkInfo?): Boolean = n?.detailedState == NetworkInfo.DetailedState.FAILED

    fun groupInfo(info: WifiP2pInfo?, group: WifiP2pGroup?): P2pGroupInfo {
        val iface = group?.`interface`
        val owner = group?.owner
        return P2pGroupInfo(
            networkName = group?.networkName,
            interfaceName = iface,
            frequencyMhz = group?.frequency?.takeIf { it > 0 },
            netId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) group?.networkId else null,
            phoneIsOwner = info?.isGroupOwner ?: group?.isGroupOwner ?: false,
            ownerName = owner?.deviceName,
            ownerAddress = owner?.deviceAddress,
            ownerIp = info?.groupOwnerAddress?.hostAddress,
            clients = group?.clientList.orEmpty().map { "${it.deviceName} (${it.deviceAddress})" },
            phoneAddresses = P2pNet.addresses(iface),
        )
    }

    /** Todos los extras de un broadcast en una línea (el grupo, sin passphrase). */
    fun extras(intent: Intent): String {
        val bundle = intent.extras ?: return "sin extras"
        return bundle.keySet().sorted().joinToString(" · ") { key -> "$key=${value(bundle, key)}" }
    }

    private fun value(bundle: Bundle, key: String): String = when (val v = bundle.get(key)) {
        is WifiP2pGroup -> "{${group(v)}}"
        is WifiP2pInfo -> "{${info(v)}}"
        is WifiP2pDevice -> "{${device(v)}}"
        is WifiP2pDeviceList -> "{${v.deviceList.size} peers}"
        is NetworkInfo -> "{${network(v)}}"
        null -> "null"
        else -> oneLine(v.toString())
    }

    fun oneLine(s: String): String = s.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
}
